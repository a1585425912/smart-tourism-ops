package com.snowworld.agent;

import com.snowworld.agent.llm.FakeLlmClient;
import com.snowworld.agent.llm.LlmClient;
import com.snowworld.agent.llm.LlmClientProvider;
import com.snowworld.agent.llm.LlmTypes.ChatRequest;
import com.snowworld.agent.llm.LlmTypes.ChatResult;
import com.snowworld.common.DataMeta;
import com.snowworld.common.TimeRange;
import com.snowworld.flow.FlowQueryService;
import com.snowworld.flow.OpsInsightService;
import com.snowworld.inspection.InspectionQueryPort;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Agent 测试用的替身: 业务查询契约的内存实现(@Primary, 覆盖主分支中的真实实现)与可切换的模型客户端。
 * 所有测试都不会访问真实的模型服务。
 */
@TestConfiguration
public class AgentTestSupport {

    static final OffsetDateTime AS_OF = OffsetDateTime.parse("2026-02-18T14:00:00+08:00");

    public static class StubFlow implements FlowQueryService {
        public String quality = "GOOD";
        public boolean missing;
        public final List<TimeRange> ranges = new ArrayList<>();

        public void reset() {
            quality = "GOOD";
            missing = false;
            ranges.clear();
        }

        private DataMeta meta() {
            return missing ? DataMeta.missing(List.of("flow-demo"), "DEMO") : new DataMeta(AS_OF, List.of("flow-demo"), "DEMO", quality);
        }

        @Override
        public FlowSummary summary(long siteId, TimeRange range) {
            ranges.add(range);
            if (missing) {
                return new FlowSummary(siteId, "景区", range.start(), range.end(), null, null, null, null, null, 30000, null,
                        "UNKNOWN", null, null, List.of(), meta());
            }
            return new FlowSummary(siteId, "景区", range.start(), range.end(), 12345L, 8000L, 4345L, 6789L, AS_OF.minusHours(1),
                    30000, new BigDecimal("0.845"), "WARN", new BigDecimal("0.12"), null, List.of(), meta());
        }

        @Override
        public FlowTrend trend(long siteId, Long zoneId, String metric, String granularity, TimeRange range) {
            List<TrendPoint> points = missing ? List.of() : List.of(
                    new TrendPoint(AS_OF.minusHours(3), new BigDecimal("3100")),
                    new TrendPoint(AS_OF.minusHours(2), new BigDecimal("5200")),
                    new TrendPoint(AS_OF.minusHours(1), new BigDecimal("6789")));
            return new FlowTrend(siteId, zoneId, metric, granularity, "last", points, List.of(), meta());
        }

        @Override
        public List<ZoneLoad> zoneLoads(long siteId) {
            return missing ? List.of() : List.of(
                    new ZoneLoad(1, "大滑梯区", "ATTRACTION", 1900L, 2000, new BigDecimal("0.95"), "WARN", AS_OF, "计数器", meta()),
                    new ZoneLoad(2, "入口广场", "ENTRANCE", 300L, 1500, new BigDecimal("0.2"), "LOW", AS_OF, "计数器", meta()));
        }
    }

    public static class StubInsight implements OpsInsightService {
        private static final DataMeta META = new DataMeta(AS_OF, List.of("ops-demo"), "DEMO", "GOOD");

        @Override
        public List<QueueStatus> queues(long siteId) {
            return List.of(new QueueStatus(1, "大滑梯", "RIDE", siteId, 1L, 126.6, 45.78, 420L, new BigDecimal("6"),
                    new BigDecimal("70"), null, "排队人数/服务能力", AS_OF, List.of(), META));
        }

        @Override
        public DailyForecast dailyForecast(long siteId, int days) {
            List<DailyPoint> points = new ArrayList<>();
            LocalDate today = LocalDate.of(2026, 2, 18);
            for (int i = 1; i <= days; i++) {
                points.add(new DailyPoint(today.plusDays(i), BigDecimal.valueOf(20000 + i * 1000L), BigDecimal.valueOf(18000),
                        BigDecimal.valueOf(30000), null, i == 1 ? "春节" : null, i == 1, null, List.of("节假日")));
            }
            return new DailyForecast(siteId, "景区", "ENTRY", new ForecastModel("ridge", "v1", AS_OF, List.of("holiday")), points,
                    new Backtest("ridge", "v1", today.minusDays(30), today.minusDays(1), 30, new BigDecimal("0.118"),
                            new BigDecimal("1500"), "naive", new BigDecimal("0.21"), new BigDecimal("2600")), META);
        }

        @Override
        public HourlyForecast hourlyForecast(long siteId, LocalDate date) {
            return new HourlyForecast(siteId, date, List.of(), null, List.of(), List.of(), META);
        }

        @Override
        public BusinessSummary businessSummary(long siteId, LocalDate start, LocalDate endExclusive) {
            return new BusinessSummary(siteId, start, endExclusive, "CNY", "支付口径", List.of(
                    new CategoryStat("HOTEL", new BigDecimal("356000.50"), 800L, 1500L, 3, new BigDecimal("0.82")),
                    new CategoryStat("CATERING", new BigDecimal("128000"), 4200L, 6100L, 5, null)), List.of(), List.of(), META);
        }

        @Override
        public GroupStats groupStats(long siteId, LocalDate start, LocalDate endExclusive) {
            return new GroupStats(42, 1680, List.of(new AgencyStat(1, "北国旅行社", 12, 480)),
                    List.of(new OriginStat("广东", 15, 600)), List.of(), META);
        }

        @Override
        public VisitorProfile visitorProfile(long siteId, YearMonth month) {
            Map<String, List<Bucket>> dims = new LinkedHashMap<>();
            dims.put("ORIGIN", List.of(new Bucket("广东", 3200, new BigDecimal("0.32")), new Bucket("未知", 800, new BigDecimal("0.08"))));
            dims.put("AGE", List.of(new Bucket("25-34岁", 4100, new BigDecimal("0.41"))));
            return new VisitorProfile(siteId, month.toString(), 10000, new BigDecimal("0.35"), dims, List.of(), META);
        }
    }

    public static class StubInspection implements InspectionQueryPort {
        public final Map<String, DraftResult> drafts = new LinkedHashMap<>();
        public final List<String> draftCalls = new ArrayList<>();

        public void reset() {
            drafts.clear();
            draftCalls.clear();
        }

        @Override
        public InspectionSummary summary(List<Long> siteIds, TimeRange range) {
            return new InspectionSummary(Map.of("COMPLETED", 18, "IN_PROGRESS", 4), 22, 18, 2, 3,
                    List.of(new OverdueTask(501, "XJ-0501", siteIds.getFirst(), "滑梯区护栏检查", "ASSIGNED", AS_OF.minusDays(1), "巡检员甲")),
                    List.of(new OpenIssue(601, 501, "XJ-0501", siteIds.getFirst(), "护栏松动", "HIGH", "OPEN", AS_OF.minusDays(1))), AS_OF);
        }

        @Override
        public DraftResult createAgentDraft(long userId, long siteId, Long zoneId, String title, String description,
                                            List<String> basisRefs, long agentRunId, String idempotencyKey) {
            draftCalls.add(idempotencyKey);
            DraftResult existing = drafts.get(idempotencyKey);
            if (existing != null) {
                return new DraftResult(existing.taskId(), existing.taskNo(), false);
            }
            DraftResult created = new DraftResult(900 + drafts.size(), "XJ-D" + (900 + drafts.size()), true);
            drafts.put(idempotencyKey, created);
            return created;
        }
    }

    /** 可切换的模型客户端: 默认本地规则模拟器, 测试可替换为脚本化或故障注入实现。 */
    public static class SwitchableLlm implements LlmClientProvider {
        private final FakeLlmClient fake;
        private LlmClient override;

        SwitchableLlm(FakeLlmClient fake) {
            this.fake = fake;
        }

        public void use(LlmClient client) {
            this.override = client;
        }

        public void reset() {
            this.override = null;
        }

        public FakeLlmClient fake() {
            return fake;
        }

        @Override
        public LlmClient current() {
            return override != null ? override : fake;
        }
    }

    /** 按顺序返回预设响应的模型; 响应用尽后重复最后一个。 */
    public static class ScriptedLlm implements LlmClient {
        private final Deque<Function<ChatRequest, ChatResult>> script = new ArrayDeque<>();
        public final List<ChatRequest> requests = new ArrayList<>();
        private Function<ChatRequest, ChatResult> last;

        public ScriptedLlm then(Function<ChatRequest, ChatResult> step) {
            script.add(step);
            return this;
        }

        public ScriptedLlm thenText(String content) {
            return then(r -> new ChatResult(content, List.of(), "stop", null, 10, 10, "scripted"));
        }

        @Override
        public String provider() {
            return "fake";
        }

        @Override
        public String model() {
            return "scripted";
        }

        @Override
        public ChatResult chat(ChatRequest request) {
            requests.add(request);
            if (!script.isEmpty()) {
                last = script.poll();
            }
            return last.apply(request);
        }
    }

    @Bean
    @Primary
    StubFlow stubFlow() {
        return new StubFlow();
    }

    @Bean
    @Primary
    StubInsight stubInsight() {
        return new StubInsight();
    }

    @Bean
    @Primary
    StubInspection stubInspection() {
        return new StubInspection();
    }

    @Bean
    @Primary
    SwitchableLlm switchableLlm(FakeLlmClient fake) {
        return new SwitchableLlm(fake);
    }
}
