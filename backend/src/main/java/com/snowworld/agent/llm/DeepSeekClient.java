package com.snowworld.agent.llm;

import com.snowworld.agent.AgentJson;
import com.snowworld.common.SnowProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * DeepSeek 云端模型客户端(OpenAI 兼容 /chat/completions)。
 * 模型名、地址、密钥全部来自配置; 密钥只出现在请求头, 不写日志。
 */
@Component
public class DeepSeekClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekClient.class);

    private final SnowProperties props;
    private final HttpClient http;

    public DeepSeekClient(SnowProperties props) {
        this.props = props;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public String provider() {
        return "deepseek";
    }

    @Override
    public String model() {
        return props.ai().model();
    }

    @Override
    public LlmTypes.ChatResult chat(LlmTypes.ChatRequest request) {
        SnowProperties.Ai ai = props.ai();
        if (ai.apiKey() == null || ai.apiKey().isBlank()) {
            throw new LlmException("NO_API_KEY", "未配置 DeepSeek API Key", false);
        }
        String body = AgentJson.write(buildBody(request, ai.model()));
        HttpRequest req = HttpRequest.newBuilder(URI.create(trimSlash(ai.baseUrl()) + "/chat/completions"))
                .timeout(Duration.ofSeconds(ai.requestTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + ai.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw new LlmException("TIMEOUT", "DeepSeek 请求超时", true, e);
        } catch (IOException e) {
            throw new LlmException("NETWORK", "无法连接 DeepSeek: " + e.getMessage(), true, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("INTERRUPTED", "请求被中断", false, e);
        }
        int status = resp.statusCode();
        if (status != 200) {
            log.warn("DeepSeek HTTP {}", status);
            throw mapError(status, resp.body());
        }
        return parse(resp.body());
    }

    static ObjectNode buildBody(LlmTypes.ChatRequest request, String model) {
        ObjectNode root = AgentJson.object();
        root.put("model", model);
        root.put("stream", false);
        root.put("temperature", 0.2);
        if (model.toLowerCase(java.util.Locale.ROOT).contains("deepseek")) {
            root.put("reasoning_effort", "low");
        }
        if (request.maxTokens() > 0) {
            root.put("max_tokens", request.maxTokens());
        }
        ArrayNode messages = root.putArray("messages");
        for (LlmTypes.Message m : request.messages()) {
            ObjectNode n = messages.addObject();
            n.put("role", m.role());
            if (m.content() != null) {
                n.put("content", m.content());
            } else {
                n.putNull("content");
            }
            if (m.reasoningContent() != null) {
                n.put("reasoning_content", m.reasoningContent());
            }
            if ("tool".equals(m.role())) {
                n.put("tool_call_id", m.toolCallId());
            }
            if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                ArrayNode calls = n.putArray("tool_calls");
                for (LlmTypes.ToolCall c : m.toolCalls()) {
                    ObjectNode cn = calls.addObject();
                    cn.put("id", c.id());
                    cn.put("type", "function");
                    ObjectNode fn = cn.putObject("function");
                    fn.put("name", c.name());
                    fn.put("arguments", c.arguments());
                }
            }
        }
        if (request.tools() != null && !request.tools().isEmpty()) {
            ArrayNode tools = root.putArray("tools");
            for (LlmTypes.ToolSpec t : request.tools()) {
                ObjectNode tn = tools.addObject();
                tn.put("type", "function");
                ObjectNode fn = tn.putObject("function");
                fn.put("name", t.name());
                fn.put("description", t.description());
                fn.set("parameters", t.parameters());
            }
        }
        if (request.jsonMode()) {
            root.putObject("response_format").put("type", "json_object");
        }
        return root;
    }

    static LlmTypes.ChatResult parse(String body) {
        JsonNode root = AgentJson.parseOrNull(body);
        if (root == null || !root.path("choices").isArray() || root.path("choices").isEmpty()) {
            throw new LlmException("BAD_RESPONSE", "DeepSeek 返回内容无法解析", true);
        }
        JsonNode choice = root.path("choices").get(0);
        JsonNode msg = choice.path("message");
        List<LlmTypes.ToolCall> calls = new ArrayList<>();
        for (JsonNode c : msg.path("tool_calls")) {
            calls.add(new LlmTypes.ToolCall(c.path("id").asString(), c.path("function").path("name").asString(),
                    c.path("function").path("arguments").asString("{}")));
        }
        JsonNode usage = root.path("usage");
        return new LlmTypes.ChatResult(AgentJson.text(msg, "content"), calls, AgentJson.text(choice, "finish_reason"),
                AgentJson.text(msg, "reasoning_content"), usage.path("prompt_tokens").asInt(0),
                usage.path("completion_tokens").asInt(0), AgentJson.text(root, "model"));
    }

    static LlmException mapError(int status, String body) {
        String detail = AgentJson.truncate(body == null ? "" : body.replaceAll("\\s+", " "), 200);
        return switch (status) {
            case 401, 403 -> new LlmException("AUTH_FAILED", "DeepSeek 鉴权失败, 请检查 API Key", false);
            case 402 -> new LlmException("INSUFFICIENT_BALANCE", "DeepSeek 账户余额不足", false);
            case 400, 422 -> new LlmException("BAD_REQUEST", "DeepSeek 拒绝了请求: " + detail, false);
            case 429 -> new LlmException("RATE_LIMITED", "DeepSeek 限流, 请稍后重试", true);
            default -> new LlmException("UPSTREAM_" + status, "DeepSeek 服务暂不可用 (HTTP " + status + ")", status >= 500);
        };
    }

    private static String trimSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
