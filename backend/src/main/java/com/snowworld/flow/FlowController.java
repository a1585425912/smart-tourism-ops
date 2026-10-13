package com.snowworld.flow;

import com.snowworld.audit.AuditService;
import com.snowworld.auth.ScopeService;
import com.snowworld.common.ApiException;
import com.snowworld.common.ClockConfig;
import com.snowworld.common.ErrorCode;
import com.snowworld.common.TimeRange;
import com.snowworld.flow.FlowIngestService.FlowRecord;
import com.snowworld.forecast.ForecastService;
import com.snowworld.job.JobService;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 客流、排队、预测与日历接口。 */
@RestController
@RequestMapping("/api/v1")
public class FlowController {

    private static final Set<String> IMPORT_METRICS = Set.of("ENTRY", "EXIT", "IN_PARK", "ZONE_COUNT");
    private static final int MAX_IMPORT_ROWS = 50_000;

    public record ImportError(int line, String message) {
    }

    public record ImportResult(String batchId, int total, int inserted, int duplicates, List<ImportError> errors) {
    }

    private final FlowQueryService flows;
    private final OpsInsightService insight;
    private final ForecastService forecast;
    private final FlowIngestService ingest;
    private final ScopeService scope;
    private final JobService jobs;
    private final JdbcClient jdbc;
    private final AuditService audit;
    private final Clock clock;

    public FlowController(FlowQueryService flows, OpsInsightService insight, ForecastService forecast, FlowIngestService ingest,
                          ScopeService scope, JobService jobs, JdbcClient jdbc, AuditService audit, Clock clock) {
        this.flows = flows;
        this.insight = insight;
        this.forecast = forecast;
        this.ingest = ingest;
        this.scope = scope;
        this.jobs = jobs;
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    @GetMapping("/flows/summary")
    @PreAuthorize("hasAuthority('FLOW_READ')")
    public FlowQueryService.FlowSummary summary(@RequestParam long siteId,
                                                @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime start,
                                                @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime end) {
        scope.requireSite(siteId);
        return flows.summary(siteId, new TimeRange(start, end));
    }

    @GetMapping("/flows/trend")
    @PreAuthorize("hasAuthority('FLOW_READ')")
    public FlowQueryService.FlowTrend trend(@RequestParam long siteId, @RequestParam(required = false) Long zoneId,
                                            @RequestParam(defaultValue = "IN_PARK") String metric,
                                            @RequestParam(defaultValue = "HOUR") String granularity,
                                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime start,
                                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime end) {
        scope.requireSite(siteId);
        return flows.trend(siteId, zoneId, metric, granularity, new TimeRange(start, end));
    }

    @GetMapping("/flows/zones")
    @PreAuthorize("hasAuthority('FLOW_READ')")
    public List<FlowQueryService.ZoneLoad> zones(@RequestParam long siteId) {
        scope.requireSite(siteId);
        return flows.zoneLoads(siteId);
    }

    @GetMapping("/queues")
    @PreAuthorize("hasAuthority('FLOW_READ')")
    public List<OpsInsightService.QueueStatus> queues(@RequestParam long siteId) {
        scope.requireSite(siteId);
        return insight.queues(siteId);
    }

    @GetMapping("/forecasts/daily")
    @PreAuthorize("hasAuthority('FLOW_READ')")
    public OpsInsightService.DailyForecast daily(@RequestParam long siteId, @RequestParam(defaultValue = "7") int days) {
        scope.requireSite(siteId);
        return insight.dailyForecast(siteId, days);
    }

    @GetMapping("/forecasts/hourly")
    @PreAuthorize("hasAuthority('FLOW_READ')")
    public OpsInsightService.HourlyForecast hourly(@RequestParam long siteId,
                                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        scope.requireSite(siteId);
        return insight.hourlyForecast(siteId, date == null ? LocalDate.now(clock) : date);
    }

    @GetMapping("/forecasts/backtests")
    @PreAuthorize("hasAuthority('FLOW_READ')")
    public List<Map<String, Object>> backtests(@RequestParam long siteId) {
        scope.requireSite(siteId);
        return forecast.backtests(siteId);
    }

    @PostMapping("/forecasts/refresh")
    @PreAuthorize("hasAuthority('FLOW_IMPORT')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> refresh(@RequestParam long siteId) {
        scope.requireSite(siteId);
        var job = jobs.enqueue(ForecastRefreshJob.TYPE, null, siteId, Map.of("siteId", siteId), ScopeService.currentUser().getId());
        return Map.of("jobId", job.getId());
    }

    @GetMapping("/calendar")
    @PreAuthorize("hasAuthority('SITE_READ')")
    public List<Map<String, Object>> calendar(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
                                              @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
        if (!end.isAfter(start) || end.toEpochDay() - start.toEpochDay() > 92) {
            throw new ApiException(ErrorCode.RANGE_TOO_LARGE, "日期范围须为 1-92 天, end 不含当日");
        }
        Map<LocalDate, OpsInsightService.Weather> weather = forecast.loadWeather(start, end);
        List<Map<String, Object>> out = new ArrayList<>();
        for (LocalDate d = start; d.isBefore(end); d = d.plusDays(1)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", d);
            m.put("holiday", forecast.holidayName(d));
            m.put("isOffDay", forecast.isOffDay(d));
            m.put("weather", weather.get(d));
            out.add(m);
        }
        return out;
    }

    /**
     * 客流 CSV 导入。表头: source_record_id,biz_time,zone_code,metric,value; UTF-8(可带 BOM)。
     * 任一行有错则整批不入库并返回逐行错误; 重复的 source_record_id 不重复计数。
     */
    @PostMapping("/imports/flows")
    @PreAuthorize("hasAuthority('FLOW_IMPORT')")
    public ImportResult importFlows(@RequestParam("file") MultipartFile file, @RequestParam long siteId) throws IOException {
        scope.requireSite(siteId);
        String text = new String(file.getBytes(), StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        String[] lines = text.split("\r?\n");
        if (lines.length < 2 || !lines[0].trim().equalsIgnoreCase("source_record_id,biz_time,zone_code,metric,value")) {
            throw new ApiException(ErrorCode.IMPORT_FAILED, "表头必须为 source_record_id,biz_time,zone_code,metric,value");
        }
        if (lines.length - 1 > MAX_IMPORT_ROWS) {
            throw new ApiException(ErrorCode.IMPORT_FAILED, "单次最多导入 " + MAX_IMPORT_ROWS + " 行");
        }
        Map<String, Long> zoneIds = new LinkedHashMap<>();
        jdbc.sql("SELECT code, id FROM scenic_zone WHERE site_id = ? AND active").param(siteId)
                .query((rs, i) -> zoneIds.put(rs.getString(1), rs.getLong(2))).list();
        List<FlowRecord> records = new ArrayList<>();
        List<ImportError> errors = new ArrayList<>();
        OffsetDateTime now = OffsetDateTime.now(clock);
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) {
                continue;
            }
            String[] c = lines[i].split(",", -1);
            try {
                if (c.length != 5) {
                    throw new IllegalArgumentException("应为 5 列, 实际 " + c.length + " 列");
                }
                String id = c[0].trim();
                if (id.isEmpty() || id.length() > 128) {
                    throw new IllegalArgumentException("source_record_id 不能为空且不超过 128 字符");
                }
                OffsetDateTime at = parseTime(c[1].trim());
                if (at.isAfter(now.plusMinutes(10))) {
                    throw new IllegalArgumentException("biz_time 晚于当前时间");
                }
                String metric = c[3].trim().toUpperCase();
                if (!IMPORT_METRICS.contains(metric)) {
                    throw new IllegalArgumentException("metric 只支持 " + IMPORT_METRICS);
                }
                String zoneCode = c[2].trim();
                Long zoneId = null;
                if ("ZONE_COUNT".equals(metric) != !zoneCode.isEmpty()) {
                    throw new IllegalArgumentException("ZONE_COUNT 必须填写 zone_code, 其它指标不填");
                }
                if (!zoneCode.isEmpty()) {
                    zoneId = zoneIds.get(zoneCode);
                    if (zoneId == null) {
                        throw new IllegalArgumentException("区域编码不存在: " + zoneCode);
                    }
                }
                BigDecimal value = new BigDecimal(c[4].trim());
                // 负的存量值如实保留并标记为可疑, 人次不允许为负
                if (value.signum() < 0 && !"IN_PARK".equals(metric)) {
                    throw new IllegalArgumentException("value 不能为负");
                }
                records.add(new FlowRecord(id, siteId, zoneId, null, metric, value, at, value.signum() < 0 ? "SUSPECT" : "GOOD"));
            } catch (NumberFormatException e) {
                errors.add(new ImportError(i + 1, "value 不是数字"));
            } catch (IllegalArgumentException e) {
                errors.add(new ImportError(i + 1, e.getMessage()));
            }
            if (errors.size() >= 100) {
                break;
            }
        }
        String batch = UUID.randomUUID().toString().substring(0, 8);
        if (!errors.isEmpty()) {
            audit.record("FLOW_IMPORT", "SITE", String.valueOf(siteId), siteId, "FAILED", errors.size() + " 行错误");
            throw new ApiException(ErrorCode.IMPORT_FAILED, "文件中有 " + errors.size() + " 行错误, 未导入任何数据",
                    Map.of("errors", errors, "batchId", batch));
        }
        String siteCode = jdbc.sql("SELECT code FROM scenic_site WHERE id = ?").param(siteId).query(String.class).single();
        String sourceCode = "flow-csv-" + siteCode.toLowerCase();
        jdbc.sql("""
                INSERT INTO integration_source (code, name, source_type, data_mode, site_id, provider, health)
                VALUES (?, ?, 'FLOW', 'REAL', ?, 'CSV 导入', 'UNKNOWN') ON CONFLICT (code) DO NOTHING
                """).params(sourceCode, siteCode + " 客流 CSV 导入", siteId).update();
        long sourceId = jdbc.sql("SELECT id FROM integration_source WHERE code = ?").param(sourceCode).query(Long.class).single();
        FlowIngestService.IngestResult r = ingest.ingest(sourceId, "REAL", records);
        audit.record("FLOW_IMPORT", "SITE", String.valueOf(siteId), siteId, "OK", "batch=" + batch + " inserted=" + r.inserted());
        return new ImportResult(batch, r.total(), r.inserted(), r.duplicates(), List.of());
    }

    /** 接受带时区偏移的 ISO-8601, 或不带时区的本地时间(按 Asia/Shanghai 解释)。 */
    private static OffsetDateTime parseTime(String s) {
        try {
            return OffsetDateTime.parse(s);
        } catch (DateTimeParseException e) {
            try {
                return LocalDateTime.parse(s.replace(' ', 'T')).atZone(ClockConfig.BIZ_ZONE).toOffsetDateTime();
            } catch (DateTimeParseException e2) {
                throw new IllegalArgumentException("biz_time 格式应为 2026-02-18T14:00:00+08:00 或 2026-02-18 14:00:00");
            }
        }
    }
}
