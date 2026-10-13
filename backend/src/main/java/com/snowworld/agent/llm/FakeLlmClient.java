package com.snowworld.agent.llm;

import com.snowworld.agent.AgentJson;
import com.snowworld.agent.Prompts;
import com.snowworld.agent.llm.LlmTypes.ChatRequest;
import com.snowworld.agent.llm.LlmTypes.ChatResult;
import com.snowworld.agent.llm.LlmTypes.Message;
import com.snowworld.agent.llm.LlmTypes.ToolCall;
import com.snowworld.review.RuleReviewAnalyzer;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 本地规则模拟器: 不调用任何外部服务的确定性"假模型"。
 *
 * <p>它与真实模型走完全相同的编排流程——按关键词选择工具、读取工具结果、组织回答——
 * 因此可用于自动化测试与无密钥时的离线演示。它不具备语言理解能力, 界面与任务记录中会标注为 fake / local-rule-simulator。
 */
@Component
public class FakeLlmClient implements LlmClient {

    public static final String MODEL = RuleReviewAnalyzer.MODEL;

    private static final List<String> KNOWN_SITES = List.of("冰雪大世界", "太阳岛", "中央大街", "极地公园", "东北虎林园", "伏尔加庄园", "索菲亚");
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern DRAFT_INTENT = Pattern.compile("(创建|生成|安排|新建|起草|派).{0,8}巡检");
    private static final Map<String, String> HOLIDAYS = Map.of("春节", "春节", "国庆", "国庆节", "元旦", "元旦", "五一", "劳动节",
            "劳动节", "劳动节", "清明", "清明节", "端午", "端午节", "中秋", "中秋节");

    @Override
    public String provider() {
        return "fake";
    }

    @Override
    public String model() {
        return MODEL;
    }

    @Override
    public ChatResult chat(ChatRequest request) {
        String system = request.messages().stream().filter(m -> "system".equals(m.role())).map(Message::content)
                .findFirst().orElse("");
        String user = request.messages().stream().filter(m -> "user".equals(m.role())).map(Message::content)
                .findFirst().orElse("");
        if (system.startsWith("[scenario:REVIEW_ANALYSIS]")) {
            return text(reviewAnalysis(between(user, Prompts.OPEN_REVIEWS, Prompts.CLOSE_REVIEWS)));
        }
        if (system.startsWith("[scenario:DAILY_REPORT]")) {
            return text(dailyReport(AgentJson.parseOrNull(between(user, Prompts.OPEN_SNAPSHOT, Prompts.CLOSE_SNAPSHOT))));
        }
        if (system.startsWith("[scenario:MARKETING]")) {
            return text(marketing(AgentJson.parseOrNull(between(user, Prompts.OPEN_CONTEXT, Prompts.CLOSE_CONTEXT))));
        }
        return qa(request, system, between(user, Prompts.OPEN_QUESTION, Prompts.CLOSE_QUESTION));
    }

    // ---------------- 运营问答 ----------------

    private ChatResult qa(ChatRequest request, String system, String question) {
        Set<String> offered = new LinkedHashSet<>();
        if (request.tools() != null) {
            request.tools().forEach(t -> offered.add(t.name()));
        }
        Set<String> called = new LinkedHashSet<>();
        List<JsonNode> results = new ArrayList<>();
        for (Message m : request.messages()) {
            if ("assistant".equals(m.role())) {
                m.toolCalls().forEach(c -> called.add(c.name()));
            } else if ("tool".equals(m.role())) {
                JsonNode n = AgentJson.parseOrNull(m.content());
                if (n != null) {
                    results.add(n);
                }
            }
        }
        List<String> sites = siteArgs(system, question);
        if (!offered.isEmpty() && called.isEmpty()) {
            List<ToolCall> calls = firstRound(question, sites, offered);
            if (!calls.isEmpty()) {
                return new ChatResult(null, calls, "tool_calls", null, 0, 0, MODEL);
            }
        }
        if (!offered.isEmpty()) {
            // 第二轮: 读取负面热点的评价原文作为依据
            if (called.contains("get_review_summary") && !called.contains("get_review_evidence") && offered.contains("get_review_evidence")) {
                List<Long> ids = new ArrayList<>();
                for (JsonNode r : results) {
                    for (JsonNode s : r.path("sourceIds")) {
                        if (s.asString().startsWith("review:") && ids.size() < 3) {
                            ids.add(Long.parseLong(s.asString().substring(7)));
                        }
                    }
                }
                if (!ids.isEmpty()) {
                    ObjectNode args = AgentJson.object();
                    args.set("reviewIds", AgentJson.tree(ids));
                    return new ChatResult(null, List.of(call(called.size(), "get_review_evidence", args)), "tool_calls", null, 0, 0, MODEL);
                }
            }
            // 用户明确要求时, 基于已查询到的数据创建巡检草稿
            if (DRAFT_INTENT.matcher(question).find() && !called.contains("create_inspection_draft")
                    && offered.contains("create_inspection_draft")) {
                List<String> basis = new ArrayList<>();
                StringBuilder desc = new StringBuilder();
                for (JsonNode r : results) {
                    if (r.path("ok").asBoolean(false)) {
                        r.path("sourceIds").forEach(s -> {
                            if (basis.size() < 3 && !basis.contains(s.asString())) {
                                basis.add(s.asString());
                            }
                        });
                        if (desc.length() < 250) {
                            desc.append(r.path("summary").asString(""));
                        }
                    }
                }
                if (!basis.isEmpty()) {
                    ObjectNode args = AgentJson.object();
                    if (!sites.isEmpty() && sites.getFirst() != null) {
                        args.put("site", sites.getFirst());
                    }
                    args.put("title", "AI 建议巡检：" + AgentJson.truncate(question.replaceAll("\\s+", ""), 40).replace("…", ""));
                    args.put("description", AgentJson.truncate("依据查询结果建议重点检查。" + desc, 380));
                    args.set("basisSourceIds", AgentJson.tree(basis));
                    return new ChatResult(null, List.of(call(called.size(), "create_inspection_draft", args)), "tool_calls", null, 0, 0, MODEL);
                }
            }
        }
        return text(composeAnswer(results));
    }

    private List<ToolCall> firstRound(String q, List<String> sites, Set<String> offered) {
        List<String> names = new ArrayList<>();
        addIf(names, q, "get_flow_summary", "客流", "入园", "出园", "在园", "人数", "多少人", "承载", "游客量", "人流");
        addIf(names, q, "get_flow_trend", "趋势", "走势", "变化", "高峰", "峰值", "时段");
        addIf(names, q, "get_zone_loads", "区域", "热度", "拥挤", "围栏", "哪里人多");
        addIf(names, q, "get_queue_status", "排队", "等候", "等待");
        addIf(names, q, "get_forecast", "预测", "明天", "后天", "未来", "预计", "下周");
        addIf(names, q, "get_review_summary", "评价", "网评", "投诉", "口碑", "舆情", "差评", "好评", "吐槽", "反馈", "满意");
        addIf(names, q, "get_inspection_summary", "巡检", "整改", "隐患");
        addIf(names, q, "get_device_status", "设备", "摄像头", "监控", "闸机", "在线", "离线");
        addIf(names, q, "get_business_summary", "营收", "收入", "酒店", "餐饮", "业态", "零售", "消费", "入住");
        addIf(names, q, "get_group_stats", "旅行社", "团队", "导游");
        addIf(names, q, "get_visitor_profile", "画像", "客源", "年龄", "性别");
        if (names.isEmpty()) {
            names.add("get_flow_summary");
            names.add("get_review_summary");
        }
        Set<String> multiSite = Set.of("get_review_summary", "get_inspection_summary", "get_device_status");
        Set<String> timed = Set.of("get_flow_summary", "get_flow_trend", "get_review_summary", "get_inspection_summary",
                "get_business_summary", "get_group_stats");
        List<ToolCall> calls = new ArrayList<>();
        for (String name : names) {
            if (!offered.contains(name)) {
                continue;
            }
            List<String> targets = multiSite.contains(name) && sites.size() > 1 ? java.util.Collections.singletonList(null) : sites;
            for (String site : targets) {
                if (calls.size() >= 8) {
                    break;
                }
                ObjectNode args = AgentJson.object();
                if (site != null) {
                    args.put("site", site);
                }
                if (timed.contains(name)) {
                    timeArgs(q, args);
                }
                if ("get_flow_trend".equals(name)) {
                    args.put("metric", q.contains("入园") ? "ENTRY" : "IN_PARK");
                }
                calls.add(call(calls.size(), name, args));
            }
        }
        return calls;
    }

    /** 问题中提到的景区; 未提到时返回全部可访问景区(最多 3 个), 只有一个可访问景区时省略参数。 */
    private static List<String> siteArgs(String system, String question) {
        List<String[]> allowed = new ArrayList<>();
        Matcher m = Pattern.compile("可访问景区: (.*)").matcher(system);
        if (m.find()) {
            for (String part : m.group(1).split(";")) {
                Matcher pm = Pattern.compile("\\s*(.+?)\\(([^)]+)\\)\\s*").matcher(part);
                if (pm.matches()) {
                    allowed.add(new String[]{pm.group(1), pm.group(2)});
                }
            }
        }
        List<String> mentioned = new ArrayList<>();
        for (String[] site : allowed) {
            String name = site[0];
            String core = name.replaceFirst("^哈尔滨", "").replaceFirst("(风景区|景区|步行街|公园)$", "");
            if (question.contains(name) || (core.length() >= 2 && question.contains(core)) || question.contains(site[1])) {
                mentioned.add(site[1]);
            }
        }
        // 提到了可访问范围之外的景区: 原样传给工具, 由服务端返回权限错误
        for (String known : KNOWN_SITES) {
            boolean covered = allowed.stream().anyMatch(a -> a[0].contains(known));
            if (question.contains(known) && !covered) {
                mentioned.add(known);
            }
        }
        if (!mentioned.isEmpty()) {
            return mentioned;
        }
        if (allowed.size() <= 1) {
            return java.util.Collections.singletonList(null);
        }
        return allowed.stream().limit(3).map(a -> a[1]).toList();
    }

    private static void timeArgs(String q, ObjectNode args) {
        Matcher d = DATE.matcher(q);
        if (d.find()) {
            String start = d.group();
            String end = d.find() ? d.group() : start;
            args.put("startDate", start);
            args.put("endDate", end);
            return;
        }
        for (Map.Entry<String, String> h : HOLIDAYS.entrySet()) {
            if (q.contains(h.getKey())) {
                args.put("holiday", h.getValue());
                return;
            }
        }
        String period = null;
        if (q.contains("昨天") || q.contains("昨日")) {
            period = "YESTERDAY";
        } else if (q.contains("本周") || q.contains("这周")) {
            period = "THIS_WEEK";
        } else if (q.contains("30天") || q.contains("一个月") || q.contains("近一月")) {
            period = "LAST_30_DAYS";
        } else if (q.contains("本月") || q.contains("这个月")) {
            period = "THIS_MONTH";
        } else if (q.contains("7天") || q.contains("一周") || q.contains("最近") || q.contains("近期") || q.contains("这几天")) {
            period = "LAST_7_DAYS";
        } else if (q.contains("今天") || q.contains("今日") || q.contains("现在") || q.contains("当前") || q.contains("目前")) {
            period = "TODAY";
        }
        if (period != null) {
            args.put("period", period);
        }
    }

    private static String composeAnswer(List<JsonNode> results) {
        ObjectNode out = AgentJson.object();
        StringBuilder answer = new StringBuilder();
        ArrayNode facts = out.putArray("facts");
        ArrayNode evidence = out.putArray("evidence");
        ArrayNode limitations = out.putArray("limitations");
        Set<String> evidenceIds = new LinkedHashSet<>();
        int ok = 0;
        for (JsonNode r : results) {
            if (r.path("ok").asBoolean(false)) {
                ok++;
                answer.append("• ").append(r.path("summary").asString("")).append("\n");
                for (JsonNode f : r.path("facts")) {
                    if (f.path("value").isNull() || f.path("value").isMissingNode() || facts.size() >= 12) {
                        continue;
                    }
                    ObjectNode n = facts.addObject();
                    n.put("metric", f.path("metric").asString());
                    n.set("value", f.get("value"));
                    n.put("unit", f.path("unit").asString(""));
                    n.put("period", f.path("period").asString(""));
                    n.set("sourceIds", f.path("sourceIds"));
                }
                for (JsonNode e : r.path("evidence")) {
                    if (evidenceIds.size() < 6) {
                        evidenceIds.add(e.path("sourceId").asString());
                    }
                }
            } else {
                String code = r.path("error").path("code").asString("");
                String msg = r.path("error").path("message").asString("");
                answer.append("• ").append("DENIED".equals(code) ? "无法提供：" : "未能获取：").append(msg).append("\n");
            }
        }
        evidenceIds.forEach(id -> evidence.addObject().put("sourceId", id));
        if (results.isEmpty()) {
            answer.append("没有可用的查询工具或未查询到任何数据，无法回答该问题。");
            limitations.add("未执行任何数据查询。");
        } else if (ok == 0) {
            answer.insert(0, "未能获取到可用数据：\n");
        } else {
            answer.insert(0, "根据查询结果：\n");
        }
        limitations.add("当前为本地规则模拟器生成的回答，仅按关键词选择查询并汇总结果，未做语言层面的深入分析。");
        out.put("answer", answer.toString().strip());
        return AgentJson.write(out);
    }

    // ---------------- 评价分析 ----------------

    private static String reviewAnalysis(String payload) {
        ObjectNode out = AgentJson.object();
        ArrayNode results = out.putArray("results");
        JsonNode reviews = AgentJson.parseOrNull(payload);
        if (reviews != null && reviews.isArray()) {
            for (JsonNode r : reviews) {
                RuleReviewAnalyzer.Result a = RuleReviewAnalyzer.analyze(r.path("text").asString(""));
                ObjectNode n = results.addObject();
                n.set("reviewId", r.get("reviewId"));
                n.put("sentiment", a.sentiment());
                n.set("topics", AgentJson.tree(a.topics().stream().limit(3).toList()));
                n.put("summary", AgentJson.truncate(a.summary(), 40));
                ArrayNode spans = n.putArray("evidenceSpans");
                a.spans().forEach(s -> spans.addObject().put("text", s.text()).put("topic", s.topic()));
                n.put("confidence", a.confidence());
            }
        }
        return AgentJson.write(out);
    }

    // ---------------- 日报 ----------------

    private record F(String key, String metric, JsonNode value, String unit) {
        BigDecimal num() {
            return value != null && value.isNumber() ? value.decimalValue() : null;
        }

        String shown() {
            return value == null || value.isNull() ? "暂无数据" : value.isNumber() ? value.decimalValue().stripTrailingZeros().toPlainString() : value.asString();
        }
    }

    private static Map<String, F> facts(JsonNode root) {
        Map<String, F> map = new LinkedHashMap<>();
        if (root != null) {
            for (JsonNode f : root.path("facts")) {
                String key = f.path("key").asString("");
                map.put(key, new F(key, f.path("metric").asString(""), f.get("value"), f.path("unit").asString("")));
            }
        }
        return map;
    }

    private static F first(Map<String, F> facts, String prefix) {
        return facts.values().stream().filter(f -> f.key().startsWith(prefix) && f.num() != null).findFirst().orElse(null);
    }

    private static List<F> all(Map<String, F> facts, String prefix) {
        return facts.values().stream().filter(f -> f.key().startsWith(prefix) && f.num() != null).toList();
    }

    private static String dailyReport(JsonNode snapshot) {
        Map<String, F> facts = facts(snapshot);
        String site = snapshot == null ? "" : snapshot.path("site").asString("");
        String date = snapshot == null ? "" : snapshot.path("reportDate").asString("");
        ObjectNode out = AgentJson.object();
        ArrayNode analysis = out.putArray("analysis");
        ArrayNode risks = out.putArray("risks");
        ArrayNode suggestions = out.putArray("suggestions");

        F entries = first(facts, "flow.entries");
        F peak = first(facts, "flow.inParkPeak");
        F util = first(facts, "flow.utilization");
        F warn = first(facts, "zones.warn");
        F full = first(facts, "zones.full");
        StringBuilder summary = new StringBuilder(site).append(" ").append(date).append("：");
        if (entries == null) {
            summary.append("当日客流数据暂缺");
        } else {
            summary.append("入园 ").append(entries.shown()).append(" 人次");
            if (peak != null) {
                summary.append("，在园峰值 ").append(peak.shown()).append(" 人");
            }
            if (util != null) {
                summary.append("，承载率 ").append(util.shown()).append("%");
            }
        }
        F negative = first(facts, "review.negative");
        F overdue = first(facts, "inspection.overdue");
        if (negative != null) {
            summary.append("；近期负面评价 ").append(negative.shown()).append(" 条");
        }
        if (overdue != null) {
            summary.append("；逾期巡检 ").append(overdue.shown()).append(" 个");
        }
        out.put("summary", summary.append("。").toString());

        List<String> flowParas = new ArrayList<>();
        if (util != null) {
            double u = util.num().doubleValue();
            flowParas.add(u >= 100 ? "承载率已达 " + util.shown() + "%，处于饱和状态，需立即启动限流与分流。"
                    : u >= 80 ? "承载率 " + util.shown() + "% 已进入预警区间，高峰时段需要加强入口与热门区域的疏导。"
                    : "承载率 " + util.shown() + "%，整体处于可控范围。");
            if (u >= 80) {
                risks.add("在园人数接近承载上限，热门点位存在拥挤风险。");
                suggest(suggestions, "启动高峰分流预案", "在入口与热门项目增配引导人员，通过信息屏与广播引导游客前往低负荷区域，必要时分时段放行。", util.key());
            }
        } else {
            flowParas.add("当日承载率数据暂缺，无法判断拥挤程度。");
        }
        if (warn != null && full != null && (warn.num().signum() > 0 || full.num().signum() > 0)) {
            flowParas.add("区域层面有 " + warn.shown() + " 个区域预警、" + full.shown() + " 个区域饱和，应优先关注这些围栏内的秩序。");
            suggest(suggestions, "对预警区域加密巡查", "安排巡检人员对预警和饱和区域每小时巡查一次，及时处置排队与滞留。", warn.key(), full.key());
        }
        F fPeak = first(facts, "forecast.peak");
        if (fPeak != null) {
            flowParas.add("未来几天预测峰值日入园 " + fPeak.shown() + " 人次，可据此提前安排人员与物资。");
            suggest(suggestions, "按预测峰值排班", "依据预测峰值日提前调整售检票窗口、接驳车与保洁班次。", fPeak.key());
        }
        section(analysis, "客流承载研判", flowParas);

        List<String> reviewParas = new ArrayList<>();
        List<F> hot = all(facts, "review.hot.");
        if (!hot.isEmpty()) {
            reviewParas.add("负面评价集中在：" + String.join("；", hot.stream().map(h -> h.metric() + " " + h.shown() + " 条").toList()) + "。");
            risks.add("负面热点若不及时回应，可能在社交平台持续发酵。");
            suggest(suggestions, "针对负面热点制定整改清单", "由服务部门逐项核实负面热点对应的现场问题，明确责任人与完成时限，并在平台上回复说明。",
                    hot.stream().map(F::key).toArray(String[]::new));
        } else if (negative != null) {
            reviewParas.add("近期负面评价 " + negative.shown() + " 条，暂未形成集中的负面热点。");
        } else {
            reviewParas.add("评价数据暂缺。");
        }
        section(analysis, "服务口碑研判", reviewParas);

        List<String> opsParas = new ArrayList<>();
        F issues = first(facts, "inspection.openIssues");
        F offline = first(facts, "device.offline");
        if (overdue != null && overdue.num().signum() > 0) {
            opsParas.add("存在 " + overdue.shown() + " 个逾期巡检任务，需要督办。");
            suggest(suggestions, "督办逾期巡检", "由景区管理员逐个确认逾期原因，重新指派或调整截止时间。", overdue.key());
        }
        if (issues != null && issues.num().signum() > 0) {
            opsParas.add("尚有 " + issues.shown() + " 项巡检异常未关闭。");
            risks.add("未关闭的巡检异常可能影响游客安全与体验。");
        }
        if (offline != null && offline.num().signum() > 0) {
            opsParas.add("有 " + offline.shown() + " 台设备离线或故障，影响监控与客流统计的完整性。");
            suggest(suggestions, "修复离线设备", "通知运维人员排查离线设备的供电与网络，优先恢复重点点位。", offline.key());
        }
        if (opsParas.isEmpty()) {
            opsParas.add("巡检与设备方面未发现需要特别关注的问题。");
        }
        section(analysis, "运维保障研判", opsParas);
        return AgentJson.write(out);
    }

    // ---------------- 营销方案 ----------------

    private static String marketing(JsonNode ctx) {
        Map<String, F> facts = facts(ctx);
        String sites = ctx == null ? "" : ctx.path("sites").asString("");
        String goal = ctx == null ? "" : ctx.path("goal").asString("");
        ObjectNode out = AgentJson.object();
        String segment = ctx == null ? "" : ctx.path("segment").asString("");
        String names = sites.replaceAll("\\([^)]*\\)", "").replace("哈尔滨", "");
        out.put("title", AgentJson.truncate(names + "·" + (segment.isBlank() ? "多景区联游" : segment)
                + "｜" + (goal.isBlank() ? "住宿餐饮联动方案" : goal), 90));
        ArrayNode segments = out.putArray("segments");
        F origin = top(facts, "profile.ORIGIN.");
        F age = top(facts, "profile.AGE.");
        F channel = top(facts, "profile.CHANNEL.");
        for (F f : new F[]{origin, age, channel}) {
            if (f != null) {
                ObjectNode n = segments.addObject();
                n.put("name", f.metric().replace("占比", "").replace("「", "").replace("」", " "));
                n.put("evidence", "游客画像显示" + f.metric() + "为 " + f.shown() + "%。");
                n.set("share", f.value());
            }
        }
        ArrayNode packages = out.putArray("packages");
        F hotel = first(facts, "business.occupancy.HOTEL");
        F peak = first(facts, "forecast.peak");
        F groups = first(facts, "group.groups");
        F queueHot = first(facts, "review.hot.QUEUE");
        ObjectNode p1 = packages.addObject();
        p1.put("name", "景区门票 + 酒店住宿联票");
        p1.set("items", AgentJson.tree(List.of("景区门票", "合作酒店一晚", "接驳车")));
        p1.put("priceHint", "以门票与房价之和为基准给予组合优惠，具体价格由经营部门核定");
        p1.put("targetDays", "平日与非高峰周末");
        p1.put("rationale", hotel != null ? "酒店平均入住率为 " + hotel.shown() + "%，通过联票把景区客流导向住宿，提升过夜比例。"
                : "酒店经营数据暂缺，建议先以小规模试点验证联票吸引力。");
        p1.set("basis", AgentJson.tree(hotel == null ? List.of() : List.of(hotel.key())));
        ObjectNode p2 = packages.addObject();
        p2.put("name", "错峰夜场 + 特色餐饮套票");
        p2.set("items", AgentJson.tree(List.of("夜场门票", "园内特色餐饮券", "热饮券")));
        p2.put("priceHint", "夜场票叠加餐饮券，按错峰时段给予折扣，具体价格由经营部门核定");
        p2.put("targetDays", peak != null ? "预测峰值日前后的错峰时段" : "节假日错峰时段");
        p2.put("rationale", (peak != null ? "预测峰值日入园 " + peak.shown() + " 人次，" : "")
                + (queueHot != null ? "且排队等候是负面评价热点（" + queueHot.shown() + " 条），" : "")
                + "用错峰产品引导游客避开高峰并带动餐饮消费。");
        List<String> basis2 = new ArrayList<>();
        if (peak != null) {
            basis2.add(peak.key());
        }
        if (queueHot != null) {
            basis2.add(queueHot.key());
        }
        p2.set("basis", AgentJson.tree(basis2));
        if (groups != null && groups.num().signum() > 0) {
            ObjectNode p3 = packages.addObject();
            p3.put("name", "旅行社团队定制线路");
            p3.set("items", AgentJson.tree(List.of("团队快速通道", "多景区联游", "团餐")));
            p3.put("priceHint", "按团队规模阶梯定价，具体价格由经营部门与旅行社协商");
            p3.put("targetDays", "全季");
            p3.put("rationale", "统计期内入园团队 " + groups.shown() + " 个，团队客源稳定，可通过多景区联游提高停留时间。");
            p3.set("basis", AgentJson.tree(List.of(groups.key())));
        }
        out.put("summary", "围绕" + (goal.isBlank() ? "提升综合消费" : AgentJson.truncate(goal, 60)) + "，面向" + sites
                + "的主要客群，设计门票、住宿与餐饮的组合产品，并以错峰产品缓解高峰压力。");
        out.set("channels", AgentJson.tree(List.of("景区官方小程序与公众号", "在线旅行平台专题页", "合作酒店与旅行社渠道")));
        out.set("kpis", AgentJson.tree(List.of("联票销量与核销率", "游客过夜比例", "高峰时段在园人数占比", "套票相关评价的满意度")));
        out.set("risks", AgentJson.tree(List.of("画像为抽样统计，客群判断需结合票务数据复核", "联票涉及多方结算，需提前明确分账与退改规则")));
        return AgentJson.write(out);
    }

    private static F top(Map<String, F> facts, String prefix) {
        return all(facts, prefix).stream().filter(f -> !f.key().contains("未知"))
                .max(java.util.Comparator.comparing(F::num)).orElse(null);
    }

    // ---------------- 辅助 ----------------

    private static void section(ArrayNode analysis, String title, List<String> paragraphs) {
        ObjectNode n = analysis.addObject();
        n.put("title", title);
        n.set("paragraphs", AgentJson.tree(paragraphs));
    }

    private static void suggest(ArrayNode suggestions, String title, String detail, String... basis) {
        ObjectNode n = suggestions.addObject();
        n.put("title", title);
        n.put("detail", detail);
        n.set("basis", AgentJson.tree(List.of(basis)));
    }

    private static void addIf(List<String> names, String q, String tool, String... keywords) {
        for (String k : keywords) {
            if (q.contains(k)) {
                names.add(tool);
                return;
            }
        }
    }

    private static ToolCall call(int index, String name, ObjectNode args) {
        return new ToolCall("fake_call_" + index + "_" + name, name, AgentJson.write(args));
    }

    private static ChatResult text(String content) {
        return new ChatResult(content, List.of(), "stop", null, 0, 0, MODEL);
    }

    private static String between(String text, String open, String close) {
        int s = text.indexOf(open);
        int e = text.lastIndexOf(close);
        return s >= 0 && e > s ? text.substring(s + open.length(), e).strip() : text;
    }
}
