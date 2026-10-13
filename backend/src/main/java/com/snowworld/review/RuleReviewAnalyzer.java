package com.snowworld.review;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 本地规则评价分析器: 基于主题词典与情绪词表的确定性分析。
 * 用作离线演示的"本地规则模拟器"与模型不可用时的参照, 输出与模型输出走同一套校验流程。
 */
public final class RuleReviewAnalyzer {

    public static final String MODEL = "local-rule-simulator";

    private static final List<String> POSITIVE = List.of("震撼", "好看", "壮观", "漂亮", "值得", "推荐", "很棒", "不错", "满意",
            "热情", "出片", "惊艳", "开心", "美", "周到", "干净", "方便", "好吃", "专业", "值");
    private static final List<String> NEGATIVE = List.of("太久", "太贵", "不值", "失望", "差", "脏", "乱", "冷死", "坑", "排了",
            "没人管", "态度不好", "冷漠", "堵", "难找", "不方便", "摔", "混乱", "投诉", "退款", "太挤", "人太多", "排队两",
            "排队一个", "宰客", "臭", "没地方", "不合理", "差评");
    private static final List<String> NEGATORS = List.of("不", "没", "没有", "别");

    public record Span(int start, int end, String text, String topic) {
    }

    public record Result(String sentiment, List<String> topics, String summary, List<Span> spans, double confidence) {
    }

    private RuleReviewAnalyzer() {
    }

    public static Result analyze(String text) {
        if (text == null || text.strip().length() < 4) {
            return new Result("UNKNOWN", List.of(ReviewTopic.OTHER.name()), null, List.of(), 0.2);
        }
        int pos = 0;
        int neg = 0;
        for (String w : POSITIVE) {
            int idx = text.indexOf(w);
            while (idx >= 0) {
                if (negated(text, idx)) {
                    neg++;
                } else {
                    pos++;
                }
                idx = text.indexOf(w, idx + w.length());
            }
        }
        for (String w : NEGATIVE) {
            if (text.contains(w)) {
                neg++;
            }
        }
        Set<String> topics = new LinkedHashSet<>();
        List<Span> spans = new ArrayList<>();
        for (ReviewTopic topic : ReviewTopic.values()) {
            for (String kw : topic.keywords()) {
                int idx = text.indexOf(kw);
                if (idx >= 0) {
                    topics.add(topic.name());
                    Span clause = clauseAround(text, idx, topic.name());
                    if (spans.stream().noneMatch(s -> s.start() == clause.start())) {
                        spans.add(clause);
                    }
                    break;
                }
            }
        }
        if (topics.isEmpty()) {
            topics.add(ReviewTopic.OTHER.name());
        }
        String sentiment;
        if (pos == 0 && neg == 0) {
            sentiment = "NEUTRAL";
        } else if (pos > 0 && neg > 0) {
            sentiment = Math.abs(pos - neg) >= 2 ? (pos > neg ? "POSITIVE" : "NEGATIVE") : "MIXED";
        } else {
            sentiment = pos > 0 ? "POSITIVE" : "NEGATIVE";
        }
        double confidence = Math.min(0.95, 0.45 + 0.12 * (pos + neg) + 0.05 * spans.size());
        String summary = firstClause(text);
        return new Result(sentiment, List.copyOf(topics), summary, spans.stream().limit(4).toList(), confidence);
    }

    private static boolean negated(String text, int idx) {
        for (String n : NEGATORS) {
            int s = idx - n.length();
            if (s >= 0 && text.startsWith(n, s)) {
                return true;
            }
            if (s - 1 >= 0 && text.startsWith(n, s - 1) && idx - (s - 1) <= 3) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBreak(char c) {
        return "，。！？；,.!?;\n".indexOf(c) >= 0;
    }

    static Span clauseAround(String text, int idx, String topic) {
        int start = idx;
        while (start > 0 && !isBreak(text.charAt(start - 1))) {
            start--;
        }
        int end = idx;
        while (end < text.length() && !isBreak(text.charAt(end))) {
            end++;
        }
        if (end - start > 60) {
            start = Math.max(start, idx - 20);
            end = Math.min(end, idx + 40);
        }
        return new Span(start, end, text.substring(start, end), topic);
    }

    private static String firstClause(String text) {
        String s = text.strip();
        int end = 0;
        while (end < s.length() && end < 60 && !"。！？!?\n".contains(String.valueOf(s.charAt(end)))) {
            end++;
        }
        return s.substring(0, end);
    }
}
