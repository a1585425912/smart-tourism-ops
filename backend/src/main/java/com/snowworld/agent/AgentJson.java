package com.snowworld.agent;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Agent 模块内部使用的 JSON 工具。 */
public final class AgentJson {

    public static final JsonMapper MAPPER = JsonMapper.builder().build();

    private AgentJson() {
    }

    public static ObjectNode object() {
        return MAPPER.createObjectNode();
    }

    public static ArrayNode array() {
        return MAPPER.createArrayNode();
    }

    public static JsonNode tree(Object value) {
        return MAPPER.valueToTree(value);
    }

    public static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    /** 解析失败返回 null, 不抛异常。 */
    public static JsonNode parseOrNull(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String s = stripFence(text.strip());
        try {
            return MAPPER.readTree(s);
        } catch (JacksonException e) {
            return null;
        }
    }

    /** 去掉模型可能包裹的 ```json 代码块标记。 */
    static String stripFence(String s) {
        if (s.startsWith("```")) {
            int firstNl = s.indexOf('\n');
            int lastFence = s.lastIndexOf("```");
            if (firstNl > 0 && lastFence > firstNl) {
                return s.substring(firstNl + 1, lastFence).strip();
            }
        }
        return s;
    }

    public static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() || v.isContainerNode() ? null : v.asString();
    }

    public static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "…";
    }
}
