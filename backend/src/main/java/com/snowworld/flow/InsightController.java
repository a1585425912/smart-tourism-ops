package com.snowworld.flow;

import com.snowworld.auth.ScopeService;
import com.snowworld.common.ApiException;
import com.snowworld.common.PageResult;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 运营总览、多业态、团队、画像与接入状态接口。 */
@RestController
@RequestMapping("/api/v1")
public class InsightController {

    private final OpsInsightServiceImpl insight;
    private final OverviewService overview;
    private final ScopeService scope;
    private final JdbcClient jdbc;
    private final Clock clock;

    public InsightController(OpsInsightServiceImpl insight, OverviewService overview, ScopeService scope, JdbcClient jdbc, Clock clock) {
        this.insight = insight;
        this.overview = overview;
        this.scope = scope;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @GetMapping("/overview")
    @PreAuthorize("hasAuthority('FLOW_READ')")
    public OverviewService.Overview overview() {
        return overview.build(scope.allowedSiteIds());
    }

    @GetMapping("/business/summary")
    @PreAuthorize("hasAuthority('BUSINESS_READ')")
    public OpsInsightService.BusinessSummary businessSummary(@RequestParam long siteId,
                                                             @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
                                                             @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
        scope.requireSite(siteId);
        return insight.businessSummary(siteId, start, end);
    }

    @GetMapping("/business/units")
    @PreAuthorize("hasAuthority('BUSINESS_READ')")
    public List<Map<String, Object>> businessUnits(@RequestParam long siteId, @RequestParam(required = false) String category) {
        scope.requireSite(siteId);
        return insight.businessUnits(siteId, category);
    }

    @GetMapping("/business/linkage")
    @PreAuthorize("hasAuthority('BUSINESS_READ')")
    public OpsInsightServiceImpl.BusinessLinkage linkage(@RequestParam long siteId, @RequestParam(defaultValue = "30") int days) {
        scope.requireSite(siteId);
        return insight.linkage(siteId, days);
    }

    @GetMapping("/groups")
    @PreAuthorize("hasAuthority('BUSINESS_READ')")
    public PageResult<Map<String, Object>> groups(@RequestParam(required = false) List<Long> siteId,
                                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                                  @RequestParam(required = false) Long agencyId,
                                                  @RequestParam(required = false) String status,
                                                  @RequestParam(required = false) Integer page,
                                                  @RequestParam(required = false) Integer size) {
        return insight.groups(scope.resolve(siteId), date, agencyId, status, PageResult.clampPage(page), PageResult.clampSize(size));
    }

    @GetMapping("/groups/stats")
    @PreAuthorize("hasAuthority('BUSINESS_READ')")
    public OpsInsightService.GroupStats groupStats(@RequestParam long siteId,
                                                   @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
                                                   @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
        scope.requireSite(siteId);
        return insight.groupStats(siteId, start, end);
    }

    @GetMapping("/agencies")
    @PreAuthorize("hasAuthority('BUSINESS_READ')")
    public List<Map<String, Object>> agencies() {
        return insight.agencies();
    }

    @GetMapping("/profiles")
    @PreAuthorize("hasAuthority('FLOW_READ')")
    public OpsInsightService.VisitorProfile profile(@RequestParam long siteId, @RequestParam(required = false) String month) {
        scope.requireSite(siteId);
        YearMonth ym;
        try {
            ym = month == null || month.isBlank() ? YearMonth.now(clock).minusMonths(1) : YearMonth.parse(month);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("month 格式应为 YYYY-MM");
        }
        return insight.visitorProfile(siteId, ym);
    }

    /** 数据源接入状态。只返回全局数据源与授权景区的数据源; 不包含任何连接配置或密钥。 */
    @GetMapping("/integrations")
    @PreAuthorize("hasAuthority('INTEGRATION_READ')")
    public List<Map<String, Object>> integrations() {
        List<Long> allowed = scope.allowedSiteIds();
        OffsetDateTime now = OffsetDateTime.now(clock);
        return jdbc.sql("""
                SELECT id, code, name, source_type, data_mode, site_id, provider, license_note, refresh_seconds, watermark,
                       last_success_at, last_error_at, last_error, health, enabled
                FROM integration_source ORDER BY source_type, id
                """).query((rs, i) -> {
            Long siteId = (Long) rs.getObject("site_id");
            if (siteId != null && !allowed.contains(siteId)) {
                return null;
            }
            OffsetDateTime success = FlowQueryServiceImpl.ts(rs, "last_success_at");
            Integer refresh = (Integer) rs.getObject("refresh_seconds");
            String health = rs.getString("health");
            // 超过 3 个刷新周期没有成功记录即视为过期
            if ("OK".equals(health) && refresh != null && success != null
                    && Duration.between(success, now).getSeconds() > refresh * 3L) {
                health = "STALE";
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("code", rs.getString("code"));
            m.put("name", rs.getString("name"));
            m.put("sourceType", rs.getString("source_type"));
            m.put("dataMode", rs.getString("data_mode"));
            m.put("siteId", siteId);
            m.put("provider", rs.getString("provider"));
            m.put("licenseNote", rs.getString("license_note"));
            m.put("refreshSeconds", refresh);
            m.put("watermark", FlowQueryServiceImpl.ts(rs, "watermark"));
            m.put("lastSuccessAt", success);
            m.put("lastErrorAt", FlowQueryServiceImpl.ts(rs, "last_error_at"));
            m.put("lastError", rs.getString("last_error"));
            m.put("health", health);
            m.put("enabled", rs.getBoolean("enabled"));
            return m;
        }).list().stream().filter(m -> m != null).toList();
    }
}
