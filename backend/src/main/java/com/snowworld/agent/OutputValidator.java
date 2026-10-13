package com.snowworld.agent;

import com.snowworld.agent.tool.AgentTool.Evidence;
import com.snowworld.agent.tool.AgentTool.Fact;
import com.snowworld.agent.tool.ToolExecutor;
import com.snowworld.agent.tool.ToolExecutor.RunEvidence;
import com.snowworld.common.DataMeta;
import com.snowworld.review.ReviewService;
import com.snowworld.review.ReviewTopic;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 模型输出校验。原则: 来源 ID 必须属于本任务工具实际返回的集合; 数字必须能与工具结果核对;
 * 校验不通过的内容被剔除并写入 limitations, 而不是原样展示。
 */
public final class OutputValidator {

    private static final Set<String> SENTIMENTS = Set.of("POSITIVE", "NEUTRAL", "NEGATIVE", "MIXED", "UNKNOWN");
    private static final Pattern DATE_LIKE = Pattern.compile(
            "\\d{4}-\\d{1,2}-\\d{1,2}|\\d{4}年(\\d{1,2}月)?(\\d{1,2}日)?|\\d{1,2}月\\d{1,2}日|\\d{1,2}月|\\d{1,2}-\\d{1,2} \\d{1,2}:\\d{2}|\\d{1,2}:\\d{2}|\\d{1,2}-\\d{1,2}(?!\\d)");
    private static final Pattern NUMBER = Pattern.compile("(\\d[\\d,]*(?:\\.\\d+)?)(\\s*(万|%|％))?");

    /** 输出不合格, 需要修复或判定失败。 */
    public static class InvalidOutput extends RuntimeException {
        public InvalidOutput(String message) {
            super(message);
        }
    }

    private OutputValidator() {
    }

    // ---------------- 运营问答 ----------------

    public static ObjectNode qa(JsonNode out, RunEvidence ev) {
        if (out == null || !out.isObject()) {
            throw new InvalidOutput("输出不是 JSON 对象");
        }
        String answer = AgentJson.text(out, "answer");
        if (answer == null || answer.isBlank()) {
            throw new InvalidOutput("缺少 answer");
        }
        Set<String> limitations = new LinkedHashSet<>();
        ObjectNode result = AgentJson.object();
        result.put("answer", AgentJson.truncate(answer.strip(), 4000));

        ArrayNode facts = result.putArray("facts");
        int droppedFacts = 0;
        for (JsonNode f : iter(out.get("facts"))) {
            if (!f.isObject() || facts.size() >= 30) {
                continue;
            }
            List<String> ids = knownIds(f.get("sourceIds"), ev);
            JsonNode value = f.get("value");
            boolean numeric = value != null && value.isNumber();
            if (ids.isEmpty() || (numeric && !verifiable(value.decimalValue(), false, false, ev))) {
                droppedFacts++;
                continue;
            }
            ObjectNode n = facts.addObject();
            n.put("metric", AgentJson.truncate(AgentJson.text(f, "metric"), 80));
            n.set("value", value == null ? AgentJson.MAPPER.nullNode() : value);
            n.put("unit", AgentJson.truncate(AgentJson.text(f, "unit"), 20));
            n.put("period", AgentJson.truncate(AgentJson.text(f, "period"), 80));
            n.set("sourceIds", AgentJson.tree(ids));
        }
        if (droppedFacts > 0) {
            limitations.add("模型给出的 " + droppedFacts + " 项数据未能与查询结果核对或缺少有效来源，已剔除。");
        }

        ArrayNode evidence = result.putArray("evidence");
        Set<String> seen = new LinkedHashSet<>();
        int unknownEvidence = 0;
        for (JsonNode e : iter(out.get("evidence"))) {
            String id = e.isObject() ? AgentJson.text(e, "sourceId") : e.isString() ? e.asString() : null;
            if (id == null || !ev.sourceIds.contains(id)) {
                unknownEvidence++;
                continue;
            }
            seen.add(id);
        }
        // 事实引用到的来源也列入依据
        facts.forEach(f -> f.get("sourceIds").forEach(s -> seen.add(s.asString())));
        for (String id : seen) {
            if (evidence.size() >= 20) {
                break;
            }
            Evidence known = ev.evidence.get(id);
            ObjectNode n = evidence.addObject();
            n.put("sourceId", id);
            // 标签与片段一律取服务端保存的内容, 不采用模型转述
            n.put("label", known != null ? known.label() : "数据来源 " + id);
            n.put("excerpt", known != null ? known.excerpt() : null);
        }
        if (unknownEvidence > 0) {
            limitations.add("模型引用了 " + unknownEvidence + " 个不存在的依据，已剔除。");
        }

        List<String> unverified = unverifiedNumbers(answer, ev);
        if (!unverified.isEmpty()) {
            limitations.add("回答正文中的数字 " + String.join("、", unverified) + " 未能与查询结果核对，请以“事实数据”为准。");
        }
        for (JsonNode l : iter(out.get("limitations"))) {
            if (l.isString() && !l.asString().isBlank() && limitations.size() < 12) {
                limitations.add(AgentJson.truncate(l.asString().strip(), 200));
            }
        }
        limitations.addAll(ev.errors);
        if (ev.okCalls == 0) {
            limitations.add("本次未取得任何可用数据，回答不包含经核实的数据。");
        }
        String mode = ev.modes.isEmpty() ? null : DataMeta.mergeMode(ev.modes);
        if ("DEMO".equals(mode) || "MIXED".equals(mode)) {
            limitations.add("DEMO".equals(mode) ? "数据为演示数据，不代表真实运营情况。" : "数据包含演示数据。");
        }
        result.set("limitations", AgentJson.tree(limitations));
        result.put("dataAsOf", ev.dataAsOf == null ? null : ev.dataAsOf.toString());
        result.put("dataMode", mode);
        return result;
    }

    // ---------------- 评价分析 ----------------

    /** 校验单条评价的模型分析结果; 证据片段必须是脱敏原文的子串。 */
    public static ReviewService.AnalysisInput review(JsonNode r, String maskedText, String model, long runId) {
        if (r == null || !r.isObject()) {
            throw new InvalidOutput("缺少该评价的分析结果");
        }
        String sentiment = AgentJson.text(r, "sentiment");
        sentiment = sentiment == null ? "" : sentiment.strip().toUpperCase();
        boolean needsHuman = false;
        if (!SENTIMENTS.contains(sentiment)) {
            sentiment = "UNKNOWN";
            needsHuman = true;
        }
        List<String> topics = new ArrayList<>();
        for (JsonNode t : iter(r.get("topics"))) {
            if (t.isString()) {
                ReviewTopic.parse(t.asString()).map(Enum::name).filter(n -> !topics.contains(n)).ifPresent(topics::add);
            }
        }
        if (topics.size() > 3) {
            topics.subList(3, topics.size()).clear();
        }
        if (topics.isEmpty()) {
            topics.add(ReviewTopic.OTHER.name());
            needsHuman = true;
        }
        ArrayNode spans = AgentJson.array();
        int invalidSpans = 0;
        for (JsonNode s : iter(r.get("evidenceSpans"))) {
            String text = s.isObject() ? AgentJson.text(s, "text") : null;
            if (text == null || text.isBlank() || text.length() > 120) {
                invalidSpans++;
                continue;
            }
            int idx = maskedText.indexOf(text);
            if (idx < 0) {
                invalidSpans++;
                continue;
            }
            if (spans.size() >= 5) {
                break;
            }
            ObjectNode n = spans.addObject();
            n.put("start", idx);
            n.put("end", idx + text.length());
            n.put("text", text);
            n.put("topic", ReviewTopic.parse(AgentJson.text(s, "topic")).map(Enum::name).orElse(topics.getFirst()));
        }
        JsonNode conf = r.get("confidence");
        double confidence = conf != null && conf.isNumber() ? conf.asDouble() : 0.5;
        if (invalidSpans > 0 || confidence < 0.6 || "UNKNOWN".equals(sentiment)
                || (spans.isEmpty() && !"NEUTRAL".equals(sentiment))) {
            needsHuman = true;
        }
        return new ReviewService.AnalysisInput(sentiment, topics, AgentJson.truncate(AgentJson.text(r, "summary"), 120), spans,
                needsHuman, model, Prompts.VERSION, runId);
    }

    // ---------------- 通用 ----------------

    static List<String> knownIds(JsonNode node, RunEvidence ev) {
        List<String> ids = new ArrayList<>();
        for (JsonNode s : iter(node)) {
            if (s.isString() && ev.sourceIds.contains(s.asString()) && !ids.contains(s.asString())) {
                ids.add(s.asString());
            }
        }
        return ids;
    }

    /** basis 允许引用事实键或来源 ID。 */
    static List<String> knownBasis(JsonNode node, RunEvidence ev) {
        List<String> ids = new ArrayList<>();
        for (JsonNode s : iter(node)) {
            if (s.isString() && (ev.facts.containsKey(s.asString()) || ev.sourceIds.contains(s.asString()))
                    && !ids.contains(s.asString())) {
                ids.add(s.asString());
            }
        }
        return ids;
    }

    /** 文本中无法与工具结果核对的数字(忽略日期、时间和小于 100 的普通计数)。 */
    public static List<String> unverifiedNumbers(String text, RunEvidence ev) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        Matcher m = NUMBER.matcher(DATE_LIKE.matcher(text).replaceAll(" "));
        while (m.find()) {
            BigDecimal v;
            try {
                v = new BigDecimal(m.group(1).replace(",", ""));
            } catch (NumberFormatException e) {
                continue;
            }
            boolean wan = "万".equals(m.group(3));
            boolean percent = m.group(3) != null && !wan;
            if (wan) {
                v = v.multiply(BigDecimal.valueOf(10000));
            }
            if (!percent && v.compareTo(BigDecimal.valueOf(100)) < 0) {
                continue;
            }
            if (!verifiable(v, wan, percent, ev) && !out.contains(m.group().strip()) && out.size() < 6) {
                out.add(m.group().strip());
            }
        }
        return out;
    }

    static boolean verifiable(BigDecimal v, boolean approximate, boolean percent, RunEvidence ev) {
        BigDecimal n = ToolExecutor.norm(v);
        if (ev.numbers.contains(n)) {
            return true;
        }
        double x = v.doubleValue();
        for (BigDecimal k : ev.numbers) {
            double d = k.doubleValue();
            // 允许四舍五入; "万"为单位的近似表述放宽到 5%; 百分数允许由比例换算
            double tol = approximate ? Math.abs(d) * 0.05 : Math.max(0.5, Math.abs(d) * 0.001);
            if (Math.abs(d - x) <= tol) {
                return true;
            }
            if (percent && Math.abs(d * 100 - x) <= 0.5) {
                return true;
            }
        }
        return false;
    }

    static Iterable<JsonNode> iter(JsonNode node) {
        return node != null && node.isArray() ? node : List.of();
    }

    static List<String> strings(JsonNode node, int max, int maxLen) {
        List<String> out = new ArrayList<>();
        for (JsonNode s : iter(node)) {
            if (s.isString() && !s.asString().isBlank() && out.size() < max) {
                out.add(AgentJson.truncate(s.asString().strip(), maxLen));
            }
        }
        return out;
    }

    static List<Fact> factsWithPrefix(RunEvidence ev, String... prefixes) {
        return ev.facts.values().stream().filter(f -> {
            for (String p : prefixes) {
                if (f.key().startsWith(p)) {
                    return true;
                }
            }
            return false;
        }).toList();
    }
}
