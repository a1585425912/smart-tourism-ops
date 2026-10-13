package com.snowworld.agent.tool;

import com.snowworld.auth.Permission;
import java.time.OffsetDateTime;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Agent 可调用的受控业务工具。工具只做授权范围内的确定性查询或草稿写入,
 * 不接受 SQL、URL、Shell 等任意执行类参数。
 */
public interface AgentTool {

    String name();

    String description();

    /** 参数 JSON Schema(type=object)。 */
    ObjectNode parameters();

    Permission requiredPermission();

    /** 只读工具在内部异常时可有限重试; 写工具不重试, 依赖幂等键。 */
    default boolean readOnly() {
        return true;
    }

    Result execute(ToolContext ctx, JsonNode args);

    /**
     * 可核对的事实。key 形如 flow.entries@BXDSJ; value 为数字或字符串, 缺失为 null(不得用 0 代替)。
     */
    record Fact(String key, String metric, Object value, String unit, String period, List<String> sourceIds) {
    }

    record Evidence(String sourceId, String label, String excerpt) {
    }

    record Result(String summary, JsonNode data, List<Fact> facts, List<Evidence> evidence, List<String> sourceIds,
                  OffsetDateTime dataAsOf, String dataMode) {
    }

    /** 工具执行的结构化错误, 原样告知模型, 让模型承认缺失而不是编造。 */
    class ToolException extends RuntimeException {
        private final String status;

        public ToolException(String status, String message) {
            super(message);
            this.status = status;
        }

        /** DENIED / INVALID / ERROR */
        public String status() {
            return status;
        }

        public static ToolException denied(String message) {
            return new ToolException("DENIED", message);
        }

        public static ToolException invalid(String message) {
            return new ToolException("INVALID", message);
        }

        public static ToolException error(String message) {
            return new ToolException("ERROR", message);
        }
    }
}
