package com.snowworld.agent.llm;

import java.util.List;
import tools.jackson.databind.JsonNode;

/** 模型适配层的数据结构, 采用 OpenAI 兼容的 chat/completions 语义。 */
public final class LlmTypes {

    private LlmTypes() {
    }

    public record ToolCall(String id, String name, String arguments) {
    }

    /**
     * 对话消息。role: system / user / assistant / tool。
     * reasoningContent 仅在模型返回思考内容时保留, 同一轮工具调用中原样回传。
     */
    public record Message(String role, String content, List<ToolCall> toolCalls, String toolCallId,
                          String reasoningContent) {

        public static Message system(String content) {
            return new Message("system", content, List.of(), null, null);
        }

        public static Message user(String content) {
            return new Message("user", content, List.of(), null, null);
        }

        public static Message assistant(String content, List<ToolCall> toolCalls, String reasoningContent) {
            return new Message("assistant", content, toolCalls == null ? List.of() : toolCalls, null, reasoningContent);
        }

        public static Message tool(String toolCallId, String content) {
            return new Message("tool", content, List.of(), toolCallId, null);
        }
    }

    /** 工具定义, parameters 为 JSON Schema。 */
    public record ToolSpec(String name, String description, JsonNode parameters) {
    }

    public record ChatRequest(List<Message> messages, List<ToolSpec> tools, boolean jsonMode, int maxTokens) {
    }

    /** finishReason: stop / tool_calls / length / content_filter 等。 */
    public record ChatResult(String content, List<ToolCall> toolCalls, String finishReason, String reasoningContent,
                             int promptTokens, int completionTokens, String model) {

        public boolean hasToolCalls() {
            return toolCalls != null && !toolCalls.isEmpty();
        }

        public boolean truncated() {
            return "length".equals(finishReason);
        }
    }
}
