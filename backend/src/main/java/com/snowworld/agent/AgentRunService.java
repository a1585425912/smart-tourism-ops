package com.snowworld.agent;

import com.snowworld.agent.llm.LlmClient;
import com.snowworld.agent.llm.LlmClientProvider;
import com.snowworld.auth.AuthUser;
import com.snowworld.auth.Permission;
import com.snowworld.auth.ScopeService;
import com.snowworld.common.ApiException;
import com.snowworld.common.ClockConfig;
import com.snowworld.common.ErrorCode;
import com.snowworld.common.PageResult;
import com.snowworld.common.SnowProperties;
import com.snowworld.job.JobContext;
import com.snowworld.job.JobHandler;
import com.snowworld.job.JobMapper;
import com.snowworld.job.JobService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Agent 任务的创建、查询、取消与额度。任务归属于创建者, 查询、订阅与取消均校验归属。 */
@Service
public class AgentRunService {

    public static final String JOB_TYPE = "AGENT_RUN";

    public record CreateRunRequest(String scenario, String question, List<Long> siteIds, Long siteId, String reportDate,
                                   String goal, String segment, List<Long> reviewIds, Integer limit, String idempotencyKey) {
    }

    public record Created(long runId, String status) {
    }

    public record StepView(int seq, String toolName, String status, JsonNode arguments, String resultSummary,
                           List<String> sourceIds, Integer durationMs, OffsetDateTime createdAt) {
    }

    public record RunView(long id, String scenario, String status, String progress, JsonNode input, JsonNode result,
                          String errorCode, String errorMessage, String provider, String model, String promptVersion,
                          int modelCalls, int toolCalls, int tokensIn, int tokensOut, OffsetDateTime createdAt,
                          OffsetDateTime startedAt, OffsetDateTime finishedAt, List<StepView> steps) {
    }

    public record Usage(LocalDate date, int runs, int tokens, Map<String, Integer> limits) {
    }

    private final AgentRunMapper mapper;
    private final ScopeService scope;
    private final JobService jobs;
    private final JobMapper jobMapper;
    private final LlmClientProvider llmProvider;
    private final SnowProperties props;
    private final Clock clock;

    public AgentRunService(AgentRunMapper mapper, ScopeService scope, JobService jobs, JobMapper jobMapper,
                           LlmClientProvider llmProvider, SnowProperties props, Clock clock) {
        this.mapper = mapper;
        this.scope = scope;
        this.jobs = jobs;
        this.jobMapper = jobMapper;
        this.llmProvider = llmProvider;
        this.props = props;
        this.clock = clock;
    }

    public Created create(CreateRunRequest req) {
        AuthUser user = ScopeService.currentUser();
        if (req == null || req.scenario() == null) {
            throw ApiException.badRequest("scenario 不能为空");
        }
        ObjectNode input = AgentJson.object();
        List<Long> sites;
        switch (req.scenario()) {
            case "QA" -> {
                require(user, Permission.AI_USE);
                String q = req.question() == null ? "" : req.question().strip();
                if (q.length() < 2 || q.length() > 1000) {
                    throw ApiException.badRequest("问题长度需在 2-1000 字之间");
                }
                input.put("question", q);
                sites = scope.resolve(user.getId(), req.siteIds());
            }
            case "DAILY_REPORT" -> {
                require(user, Permission.REPORT_WRITE);
                if (req.siteId() == null || req.reportDate() == null) {
                    throw ApiException.badRequest("siteId 与 reportDate 不能为空");
                }
                scope.requireSite(user.getId(), req.siteId());
                LocalDate date;
                try {
                    date = LocalDate.parse(req.reportDate());
                } catch (DateTimeParseException e) {
                    throw ApiException.badRequest("reportDate 需为 YYYY-MM-DD 格式");
                }
                if (date.isAfter(LocalDate.now(clock))) {
                    throw ApiException.badRequest("不能生成未来日期的日报");
                }
                input.put("siteId", req.siteId());
                input.put("reportDate", date.toString());
                sites = List.of(req.siteId());
            }
            case "MARKETING" -> {
                require(user, Permission.MARKETING_WRITE);
                String goal = req.goal() == null ? "" : req.goal().strip();
                if (goal.length() < 2 || goal.length() > 500) {
                    throw ApiException.badRequest("营销目标长度需在 2-500 字之间");
                }
                if (req.segment() != null && req.segment().length() > 200) {
                    throw ApiException.badRequest("客群描述不能超过 200 字");
                }
                input.put("goal", goal);
                input.put("segment", req.segment() == null ? null : req.segment().strip());
                sites = scope.resolve(user.getId(), req.siteIds());
            }
            case "REVIEW_ANALYSIS" -> {
                require(user, Permission.REVIEW_WRITE);
                if (req.reviewIds() != null && req.reviewIds().size() > 500) {
                    throw ApiException.badRequest("一次最多指定 500 条评价");
                }
                if (req.siteId() != null) {
                    input.put("siteId", req.siteId());
                }
                if (req.reviewIds() != null && !req.reviewIds().isEmpty()) {
                    input.set("reviewIds", AgentJson.tree(req.reviewIds()));
                }
                input.put("limit", req.limit() == null ? 100 : Math.min(Math.max(req.limit(), 1), 500));
                sites = scope.resolve(user.getId(), req.siteId() == null ? null : List.of(req.siteId()));
            }
            default -> throw ApiException.badRequest("不支持的 scenario");
        }
        if (sites.isEmpty()) {
            throw new ApiException(ErrorCode.SITE_FORBIDDEN, "当前账号没有任何景区的数据权限");
        }
        String key = null;
        if (req.idempotencyKey() != null && !req.idempotencyKey().isBlank()) {
            if (req.idempotencyKey().length() > 60) {
                throw ApiException.badRequest("idempotencyKey 不能超过 60 字符");
            }
            key = user.getId() + ":" + req.idempotencyKey().strip();
            AgentRunMapper.RunRow existing = mapper.findByKey(key);
            if (existing != null) {
                return new Created(existing.getId(), existing.getStatus());
            }
        }
        LocalDate today = LocalDate.now(clock);
        AgentRunMapper.UsageRow usage = mapper.usage(user.getId(), today);
        if (usage != null && (usage.getRuns() >= props.ai().dailyRunsPerUser() || usage.getTokens() >= props.ai().dailyTokensPerUser())) {
            throw new ApiException(ErrorCode.QUOTA_EXCEEDED, "今日 AI 调用额度已用完，请明日再试或联系管理员");
        }
        LlmClient llm = llmProvider.current();
        AgentRunMapper.RunRow row = new AgentRunMapper.RunRow();
        row.setUserId(user.getId());
        row.setScenario(req.scenario());
        row.setSiteScope(sites.toArray(Long[]::new));
        row.setInput(input);
        row.setProvider(llm.provider());
        row.setModel(llm.model());
        row.setPromptVersion(Prompts.VERSION);
        row.setIdempotencyKey(key);
        if (mapper.insert(row) == 0) {
            AgentRunMapper.RunRow existing = mapper.findByKey(key);
            return new Created(existing.getId(), existing.getStatus());
        }
        mapper.addUsage(user.getId(), today, 1, 0);
        jobs.enqueue(JOB_TYPE, jobKey(row.getId()), row.getId(), Map.of("runId", row.getId()), user.getId());
        return new Created(row.getId(), "QUEUED");
    }

    public PageResult<RunView> listMine(String scenario, Integer page, Integer size) {
        long userId = ScopeService.currentUser().getId();
        int p = PageResult.clampPage(page);
        int s = PageResult.clampSize(size);
        String sc = scenario == null || scenario.isBlank() ? null : scenario;
        List<RunView> items = mapper.listMine(userId, sc, s, PageResult.offset(p, s)).stream().map(r -> view(r, false)).toList();
        return new PageResult<>(items, mapper.countMine(userId, sc), p, s);
    }

    public RunView get(long id) {
        return view(requireOwn(id), true);
    }

    public RunView cancel(long id) {
        AgentRunMapper.RunRow run = requireOwn(id);
        if (mapper.requestCancel(id) > 0) {
            JobMapper.JobRow job = jobMapper.findByKey(jobKey(id));
            if (job != null) {
                jobs.cancel(job.getId());
            }
            if ("QUEUED".equals(run.getStatus())) {
                // 尚未开始执行: 直接标记为已取消; 运行中的任务在下一个检查点中止
                mapper.finish(id, "CANCELLED", null, null, null, "已取消", OffsetDateTime.now(clock));
            }
        }
        return get(id);
    }

    public Usage usage() {
        LocalDate today = LocalDate.now(clock);
        AgentRunMapper.UsageRow u = mapper.usage(ScopeService.currentUser().getId(), today);
        return new Usage(today, u == null ? 0 : u.getRuns(), u == null ? 0 : u.getTokens(),
                Map.of("runs", props.ai().dailyRunsPerUser(), "tokens", props.ai().dailyTokensPerUser()));
    }

    /** 归属校验: 不是本人的任务一律按不存在处理。 */
    AgentRunMapper.RunRow requireOwn(long id) {
        AgentRunMapper.RunRow run = mapper.findById(id);
        if (run == null || run.getUserId() != ScopeService.currentUser().getId()) {
            throw ApiException.notFound("任务");
        }
        return run;
    }

    List<StepView> steps(long runId, int afterSeq) {
        return mapper.toolCalls(runId, afterSeq).stream().map(t -> new StepView(t.getSeq(), t.getToolName(), t.getStatus(),
                t.getArguments(), t.getResultSummary(), t.getSourceIds() == null ? List.of() : List.of(t.getSourceIds()),
                t.getDurationMs(), executionTime(t.getCreatedAt()))).toList();
    }

    RunView view(AgentRunMapper.RunRow r, boolean withSteps) {
        return new RunView(r.getId(), r.getScenario(), r.getStatus(), r.getProgress(), r.getInput(), r.getResult(),
                r.getErrorCode(), r.getErrorMessage(), r.getProvider(), r.getModel(), r.getPromptVersion(), r.getModelCalls(),
                r.getToolCalls(), r.getTokensIn(), r.getTokensOut(), executionTime(r.getCreatedAt()), executionTime(r.getStartedAt()), executionTime(r.getFinishedAt()),
                withSteps ? steps(r.getId(), 0) : List.of());
    }

    private OffsetDateTime executionTime(OffsetDateTime at) {
        return ClockConfig.executionTime(at, clock, props.demo().replayAt() != null);
    }

    private static void require(AuthUser user, Permission permission) {
        if (!user.has(permission)) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
    }

    static String jobKey(long runId) {
        return "agent-run:" + runId;
    }

    /** 后台 worker 入口。失败由编排器自行落库, 不依赖任务级重试, 避免重复消耗模型额度。 */
    @Component
    public static class AgentRunJobHandler implements JobHandler {

        private final AgentOrchestrator orchestrator;
        private final AgentRunMapper mapper;

        public AgentRunJobHandler(AgentOrchestrator orchestrator, AgentRunMapper mapper) {
            this.orchestrator = orchestrator;
            this.mapper = mapper;
        }

        /** 后台任务已失败或被取消但 Agent 任务仍停留在运行态(如进程崩溃后重试次数用尽)时, 同步为终态。 */
        @Scheduled(fixedDelay = 15_000, initialDelay = 20_000)
        public void reconcile() {
            mapper.reconcileInterrupted();
        }

        @Override
        public String type() {
            return JOB_TYPE;
        }

        @Override
        public int maxAttempts() {
            return 2;
        }

        @Override
        public void handle(JobMapper.JobRow job, JobContext context) {
            orchestrator.execute(job.getRefId(), context::checkpoint);
        }
    }
}
