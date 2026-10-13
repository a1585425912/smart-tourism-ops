package com.snowworld.agent;

import com.snowworld.agent.OutputValidator.InvalidOutput;
import com.snowworld.agent.tool.AgentTool.Fact;
import com.snowworld.agent.tool.ToolExecutor.RunEvidence;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 组装日报与营销方案的最终内容。事实段完全由统计快照生成; 模型撰写的文字中出现无法核对的数字时整段剔除。
 */
final class ReportBuilder {

    static final String MARKETING_DISCLAIMER = "仅为内部方案草稿，未对外发布；价格与活动内容需经营部门核定后方可执行。";

    private ReportBuilder() {
    }

    static ObjectNode build(JsonNode out, RunEvidence ev, int plannedCalls) {
        String summary = AgentJson.text(out, "summary");
        if (summary == null || summary.isBlank()) {
            throw new InvalidOutput("缺少 summary");
        }
        int[] dropped = {0};
        ObjectNode content = AgentJson.object();
        content.put("summary", OutputValidator.unverifiedNumbers(summary, ev).isEmpty()
                ? AgentJson.truncate(summary.strip(), 600) : fallbackSummary(ev, dropped));
        ArrayNode sections = content.putArray("sections");
        factSection(sections, "客流与承载", ev, List.of("get_flow_summary", "get_flow_trend", "get_zone_loads"), "flow.", "trend.", "zones.", "zone.");
        factSection(sections, "排队与预测", ev, List.of("get_queue_status", "get_forecast"), "queue.", "forecast.");
        factSection(sections, "网评舆情", ev, List.of("get_review_summary"), "review.");
        factSection(sections, "巡检与设备", ev, List.of("get_inspection_summary", "get_device_status"), "inspection.", "device.");

        for (JsonNode a : OutputValidator.iter(out.get("analysis"))) {
            if (!a.isObject() || sections.size() >= 10) {
                continue;
            }
            List<String> paragraphs = new ArrayList<>();
            for (String p : OutputValidator.strings(a.get("paragraphs"), 6, 600)) {
                if (OutputValidator.unverifiedNumbers(p, ev).isEmpty()) {
                    paragraphs.add(p);
                } else {
                    dropped[0]++;
                }
            }
            if (paragraphs.isEmpty()) {
                continue;
            }
            ObjectNode sec = sections.addObject();
            sec.put("title", AgentJson.truncate(orDefault(AgentJson.text(a, "title"), "分析"), 40));
            sec.put("kind", "ANALYSIS");
            sec.set("paragraphs", AgentJson.tree(paragraphs));
            sec.putArray("facts");
        }

        List<String> risks = new ArrayList<>();
        for (String r : OutputValidator.strings(out.get("risks"), 8, 300)) {
            if (OutputValidator.unverifiedNumbers(r, ev).isEmpty()) {
                risks.add(r);
            } else {
                dropped[0]++;
            }
        }
        content.set("risks", AgentJson.tree(risks));

        ArrayNode suggestions = content.putArray("suggestions");
        for (JsonNode sg : OutputValidator.iter(out.get("suggestions"))) {
            String title = sg.isObject() ? AgentJson.text(sg, "title") : null;
            String detail = sg.isObject() ? AgentJson.text(sg, "detail") : null;
            if (title == null || detail == null || suggestions.size() >= 8) {
                continue;
            }
            if (!OutputValidator.unverifiedNumbers(title + " " + detail, ev).isEmpty()) {
                dropped[0]++;
                continue;
            }
            ObjectNode n = suggestions.addObject();
            n.put("title", AgentJson.truncate(title, 60));
            n.put("detail", AgentJson.truncate(detail, 500));
            n.set("basis", AgentJson.tree(OutputValidator.knownBasis(sg.get("basis"), ev)));
        }

        StringBuilder coverage = new StringBuilder("统计快照共 ").append(plannedCalls).append(" 项，成功获取 ").append(ev.okCalls).append(" 项");
        if (!ev.errors.isEmpty()) {
            coverage.append("；未获取：").append(String.join("；", ev.errors));
        }
        if (dropped[0] > 0) {
            coverage.append("；模型撰写的 ").append(dropped[0]).append(" 段文字因包含无法与快照核对的数字已剔除");
        }
        coverage.append("。事实段数字全部来自统计快照，分析与建议由模型基于快照撰写。");
        content.put("coverage", coverage.toString());
        return content;
    }

    private static String fallbackSummary(RunEvidence ev, int[] dropped) {
        dropped[0]++;
        return ev.summaries.values().stream().findFirst().orElse("当日数据见下方事实段。");
    }

    private static void factSection(ArrayNode sections, String title, RunEvidence ev, List<String> toolNames, String... prefixes) {
        List<String> paragraphs = new ArrayList<>();
        for (Map.Entry<String, String> e : ev.summaries.entrySet()) {
            String tool = e.getKey().substring(0, e.getKey().indexOf('#'));
            if (toolNames.contains(tool)) {
                paragraphs.add(e.getValue());
            }
        }
        List<Fact> facts = OutputValidator.factsWithPrefix(ev, prefixes);
        ObjectNode sec = sections.addObject();
        sec.put("title", title);
        sec.put("kind", "FACT");
        if (paragraphs.isEmpty()) {
            paragraphs.add("暂无数据（相关数据未能获取）。");
        }
        sec.set("paragraphs", AgentJson.tree(paragraphs));
        ArrayNode arr = sec.putArray("facts");
        for (Fact f : facts) {
            if (f.value() == null || arr.size() >= 16) {
                continue;
            }
            ObjectNode n = arr.addObject();
            n.put("metric", f.metric());
            n.set("value", AgentJson.tree(f.value()));
            n.put("unit", f.unit());
            n.set("sourceIds", AgentJson.tree(f.sourceIds()));
        }
    }

    // ---------------- 营销方案 ----------------

    static ObjectNode marketing(JsonNode out, RunEvidence ev) {
        String summary = AgentJson.text(out, "summary");
        if (summary == null || summary.isBlank() || !out.path("packages").isArray() || out.path("packages").isEmpty()) {
            throw new InvalidOutput("缺少 summary 或 packages");
        }
        List<String> risks = new ArrayList<>();
        ObjectNode content = AgentJson.object();
        content.put("title", AgentJson.truncate(orDefault(AgentJson.text(out, "title"), "营销方案草稿"), 95));
        content.put("summary", AgentJson.truncate(summary.strip(), 800));
        note(summary, ev, risks);

        ArrayNode segments = content.putArray("segments");
        for (JsonNode sg : OutputValidator.iter(out.get("segments"))) {
            String name = sg.isObject() ? AgentJson.text(sg, "name") : null;
            if (name == null || segments.size() >= 6) {
                continue;
            }
            ObjectNode n = segments.addObject();
            n.put("name", AgentJson.truncate(name, 40));
            String evidence = AgentJson.text(sg, "evidence");
            n.put("evidence", AgentJson.truncate(evidence, 300));
            note(evidence, ev, risks);
            JsonNode share = sg.get("share");
            // 占比必须是数据中存在的数字, 否则置空
            if (share != null && share.isNumber() && OutputValidator.verifiable(share.decimalValue(), false, true, ev)) {
                n.set("share", share);
            } else {
                n.putNull("share");
            }
        }

        ArrayNode packages = content.putArray("packages");
        for (JsonNode p : OutputValidator.iter(out.get("packages"))) {
            String name = p.isObject() ? AgentJson.text(p, "name") : null;
            if (name == null || packages.size() >= 5) {
                continue;
            }
            ObjectNode n = packages.addObject();
            n.put("name", AgentJson.truncate(name, 60));
            n.set("items", AgentJson.tree(OutputValidator.strings(p.get("items"), 8, 80)));
            n.put("priceHint", AgentJson.truncate(orDefault(AgentJson.text(p, "priceHint"), "待经营部门核定"), 200));
            n.put("targetDays", AgentJson.truncate(orDefault(AgentJson.text(p, "targetDays"), "待定"), 100));
            String rationale = AgentJson.text(p, "rationale");
            n.put("rationale", AgentJson.truncate(rationale, 400));
            note(rationale, ev, risks);
            n.set("basis", AgentJson.tree(OutputValidator.knownBasis(p.get("basis"), ev)));
        }
        if (packages.isEmpty()) {
            throw new InvalidOutput("packages 为空");
        }
        content.set("channels", AgentJson.tree(OutputValidator.strings(out.get("channels"), 8, 100)));
        content.set("kpis", AgentJson.tree(OutputValidator.strings(out.get("kpis"), 8, 100)));
        List<String> allRisks = new ArrayList<>(OutputValidator.strings(out.get("risks"), 8, 300));
        allRisks.addAll(risks);
        allRisks.addAll(ev.errors);
        content.set("risks", AgentJson.tree(allRisks));
        content.put("disclaimer", MARKETING_DISCLAIMER);
        return content;
    }

    private static void note(String text, RunEvidence ev, List<String> risks) {
        List<String> bad = OutputValidator.unverifiedNumbers(text, ev);
        if (!bad.isEmpty() && risks.size() < 4) {
            risks.add("方案文字中的数字 " + String.join("、", bad) + " 未能与数据核对，使用前请人工确认。");
        }
    }

    private static String orDefault(String s, String d) {
        return s == null || s.isBlank() ? d : s;
    }
}
