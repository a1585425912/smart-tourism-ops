package com.snowworld.agent.tool;

import com.snowworld.agent.AgentJson;
import com.snowworld.agent.AgentRunMapper;
import com.snowworld.agent.tool.AgentTool.Evidence;
import com.snowworld.agent.tool.AgentTool.Fact;
import com.snowworld.agent.tool.AgentTool.Result;
import com.snowworld.agent.tool.AgentTool.ToolException;
import com.snowworld.audit.AuditService;
import com.snowworld.auth.Permission;
import com.snowworld.auth.ScopeService;
import com.snowworld.auth.UserMapper;
import com.snowworld.review.PiiMasker;
import com.snowworld.scenic.SiteMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 工具执行器: 每次调用都重新判定用户的实时权限与景区范围, 校验参数, 执行并落库。
 * 模型只能看到这里返回的 JSON, 无法绕过它访问数据。
 */
@Component
public class ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);
    private static final int READ_RETRIES = 2;

    /**
     * 一次任务内累计的可核对证据: 来源 ID、事实、证据片段、出现过的全部数字。
     */
    public static class RunEvidence {
        public final Set<String> sourceIds = new LinkedHashSet<>();
        public final Map<String, Fact> facts = new LinkedHashMap<>();
        public final Map<String, Evidence> evidence = new LinkedHashMap<>();
        public final Set<BigDecimal> numbers = new HashSet<>();
        public final List<String> errors = new ArrayList<>();
        public final List<String> modes = new ArrayList<>();
        /** 各次成功调用的中文摘要, 键为 工具名#序号 */
        public final Map<String, String> summaries = new LinkedHashMap<>();
        public OffsetDateTime dataAsOf;
        public int okCalls;
        public int seq;
    }

    /** 回传给模型的内容与执行状态。 */
    public record Outcome(String status, String content, Result result) {
        public boolean ok() {
            return "OK".equals(status);
        }
    }

    private final OpsTools tools;
    private final ScopeService scope;
    private final UserMapper userMapper;
    private final SiteMapper siteMapper;
    private final AgentRunMapper runMapper;
    private final AuditService audit;
    private final Clock clock;

    public ToolExecutor(OpsTools tools, ScopeService scope, UserMapper userMapper, SiteMapper siteMapper,
                        AgentRunMapper runMapper, AuditService audit, Clock clock) {
        this.tools = tools;
        this.scope = scope;
        this.userMapper = userMapper;
        this.siteMapper = siteMapper;
        this.runMapper = runMapper;
        this.audit = audit;
        this.clock = clock;
    }

    /** 用户当前有权使用的工具(用于构造模型的工具列表)。 */
    public List<AgentTool> availableFor(long userId) {
        Set<Permission> perms = Permission.forRoles(userMapper.findRoles(userId));
        return tools.all().stream().filter(t -> perms.contains(t.requiredPermission())).toList();
    }

    public Outcome execute(AgentRunMapper.RunRow run, RunEvidence ev, String callId, String toolName, String rawArgs) {
        long t0 = System.currentTimeMillis();
        int seq = ++ev.seq;
        JsonNode args = AgentJson.parseOrNull(rawArgs == null || rawArgs.isBlank() ? "{}" : rawArgs);
        String status;
        String summary;
        Result result = null;
        try {
            AgentTool tool = tools.find(toolName);
            if (tool == null) {
                throw ToolException.invalid("不存在的工具: " + AgentJson.truncate(toolName, 40));
            }
            if (args == null) {
                throw ToolException.invalid("工具参数不是合法 JSON");
            }
            // 实时权限: 角色或景区授权在任务运行期间被撤销时立即生效
            Set<Permission> perms = Permission.forRoles(userMapper.findRoles(run.getUserId()));
            if (!perms.contains(tool.requiredPermission())) {
                throw ToolException.denied("当前用户没有使用该工具的权限");
            }
            List<Long> live = scope.allowedSiteIds(run.getUserId());
            List<Long> allowed = List.of(run.getSiteScope()).stream().filter(live::contains).toList();
            ToolSupport.validate(tool.parameters(), args);
            ToolContext ctx = new ToolContext(run.getId(), run.getUserId(), allowed, perms, siteMapper.findAll(),
                    OffsetDateTime.now(clock), seq, Set.copyOf(ev.sourceIds));
            result = invoke(tool, ctx, args);
            status = "OK";
            summary = result.summary();
        } catch (ToolException e) {
            status = e.status();
            summary = e.getMessage();
        } catch (RuntimeException e) {
            log.warn("tool {} failed in run {}: {}", toolName, run.getId(), e.toString());
            status = "ERROR";
            summary = "工具执行失败, 暂时无法获取该数据";
        }
        ObjectNode payload = AgentJson.object();
        if (result != null) {
            payload.put("ok", true);
            payload.put("summary", result.summary());
            payload.set("facts", AgentJson.tree(result.facts()));
            payload.set("evidence", AgentJson.tree(result.evidence()));
            payload.set("sourceIds", AgentJson.tree(result.sourceIds()));
            payload.put("dataAsOf", result.dataAsOf() == null ? null : result.dataAsOf().toString());
            payload.put("dataMode", result.dataMode());
            payload.set("data", result.data());
            collect(ev, result);
            ev.summaries.put(toolName + "#" + seq, result.summary());
        } else {
            payload.put("ok", false);
            ObjectNode err = payload.putObject("error");
            err.put("code", status);
            err.put("message", summary);
            ev.errors.add(switch (status) {
                case "DENIED" -> "权限限制：" + summary;
                case "INVALID" -> "查询参数无效：" + summary;
                default -> "数据获取失败：" + summary;
            });
        }
        AgentRunMapper.ToolCallRow row = new AgentRunMapper.ToolCallRow();
        row.setRunId(run.getId());
        row.setSeq(seq);
        row.setCallId(AgentJson.truncate(callId, 79));
        row.setToolName(AgentJson.truncate(toolName, 47));
        row.setArguments(redact(args));
        row.setStatus(status);
        row.setResultSummary(AgentJson.truncate(summary, 299));
        row.setResult(payload);
        row.setSourceIds(result == null ? new String[0]
                : result.sourceIds().stream().map(s -> AgentJson.truncate(s, 79)).toArray(String[]::new));
        row.setDurationMs((int) (System.currentTimeMillis() - t0));
        runMapper.insertToolCall(row);
        runMapper.addToolCall(run.getId());
        if ("DENIED".equals(status)) {
            audit.record(run.getUserId(), "agent", "AGENT_TOOL_DENIED", "AGENT_RUN", String.valueOf(run.getId()), null, "DENIED",
                    toolName + ": " + summary);
        } else if (result != null && "create_inspection_draft".equals(toolName)) {
            audit.record(run.getUserId(), "agent", "AGENT_INSPECTION_DRAFT", "AGENT_RUN", String.valueOf(run.getId()), null, "OK", summary);
        }
        return new Outcome(status, AgentJson.write(payload), result);
    }

    private Result invoke(AgentTool tool, ToolContext ctx, JsonNode args) {
        int attempts = tool.readOnly() ? READ_RETRIES + 1 : 1;
        RuntimeException last = null;
        for (int i = 0; i < attempts; i++) {
            try {
                return tool.execute(ctx, args);
            } catch (ToolException | com.snowworld.common.ApiException e) {
                if (e instanceof com.snowworld.common.ApiException api) {
                    throw switch (api.getCode()) {
                        case SITE_FORBIDDEN, FORBIDDEN -> ToolException.denied(api.getMessage());
                        case BAD_REQUEST, RANGE_TOO_LARGE, VALIDATION_FAILED -> ToolException.invalid(api.getMessage());
                        default -> ToolException.error(api.getMessage());
                    };
                }
                throw e;
            } catch (RuntimeException e) {
                last = e;
            }
        }
        throw last;
    }

    private static void collect(RunEvidence ev, Result r) {
        ev.okCalls++;
        ev.sourceIds.addAll(r.sourceIds());
        r.facts().forEach(f -> {
            ev.facts.put(f.key(), f);
            if (f.value() instanceof Number n) {
                ev.numbers.add(norm(new BigDecimal(n.toString())));
            }
        });
        r.evidence().forEach(e -> ev.evidence.put(e.sourceId(), e));
        collectNumbers(r.data(), ev.numbers);
        ev.dataAsOf = ToolSupport.earliest(ev.dataAsOf, r.dataAsOf());
        if (r.dataMode() != null) {
            ev.modes.add(r.dataMode());
        }
    }

    private static void collectNumbers(JsonNode node, Set<BigDecimal> out) {
        if (node == null) {
            return;
        }
        if (node.isNumber()) {
            out.add(norm(node.decimalValue()));
        } else if (node.isContainer()) {
            node.forEach(child -> collectNumbers(child, out));
        }
    }

    public static BigDecimal norm(BigDecimal v) {
        BigDecimal s = v.stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0) : s;
    }

    /** 落库前对参数做脱敏与截断。 */
    private static JsonNode redact(JsonNode args) {
        if (args == null || !args.isObject()) {
            return AgentJson.object();
        }
        ObjectNode out = AgentJson.object();
        args.forEachEntry((k, v) -> {
            if (v.isString()) {
                out.put(k, AgentJson.truncate(PiiMasker.mask(v.asString()), 200));
            } else {
                out.set(k, v);
            }
        });
        return out;
    }
}
