package com.snowworld.flow;

import com.fasterxml.jackson.annotation.JsonRawValue;
import com.snowworld.common.ClockConfig;
import com.snowworld.common.DataMeta;
import com.snowworld.common.TimeRange;
import com.snowworld.scenic.SiteMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** 运营总览: 汇总授权景区"今天"(业务时钟)的客流、区域负荷、设备、网评、巡检、排队与团队, 并生成异常提示。 */
@Service
public class OverviewService {

    public record ZoneCount(int warn, int full, int total) {
    }

    public record DeviceCount(int online, int total, int offline) {
    }

    public record ReviewCount(int today, int negative, BigDecimal avgRating) {
    }

    public record InspectionCount(int open, int overdue, int openIssues) {
    }

    public record QueueBrief(String name, BigDecimal waitMin) {
    }

    public record GroupCount(int groups, int visitors) {
    }

    public record OverviewSite(long siteId, String name, String shortName, String status, int capacity,
                               @JsonRawValue String center, FlowQueryService.FlowSummary today, ZoneCount zones,
                               DeviceCount devices, ReviewCount reviews, InspectionCount inspections,
                               List<QueueBrief> queues, GroupCount groupsToday) {
    }

    public record Totals(Long entries, Long inPark, long capacity) {
    }

    public record Alert(String level, String type, long siteId, String title, String detail, OffsetDateTime at, String ref) {
    }

    public record Overview(OffsetDateTime now, List<OverviewSite> sites, Totals totals, List<Alert> alerts, DataMeta meta) {
    }

    private final JdbcClient jdbc;
    private final SiteMapper siteMapper;
    private final FlowQueryService flows;
    private final OpsInsightService insight;
    private final Clock clock;

    public OverviewService(JdbcClient jdbc, SiteMapper siteMapper, FlowQueryService flows, OpsInsightService insight, Clock clock) {
        this.jdbc = jdbc;
        this.siteMapper = siteMapper;
        this.flows = flows;
        this.insight = insight;
        this.clock = clock;
    }

    public Overview build(List<Long> siteIds) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        OffsetDateTime dayStart = today.atStartOfDay(ClockConfig.BIZ_ZONE).toOffsetDateTime();
        TimeRange range = new TimeRange(dayStart, dayStart.plusDays(1));
        List<OverviewSite> sites = new ArrayList<>();
        List<Alert> alerts = new ArrayList<>();
        Set<String> sources = new HashSet<>();
        Set<String> modes = new HashSet<>();
        Long entries = null;
        Long inPark = null;
        long capacity = 0;
        OffsetDateTime asOf = null;
        boolean stale = false;

        for (SiteMapper.SiteRow site : siteMapper.findAll()) {
            if (!siteIds.contains(site.getId())) {
                continue;
            }
            long id = site.getId();
            FlowQueryService.FlowSummary summary = flows.summary(id, range);
            sources.addAll(summary.meta().sourceIds());
            modes.add(summary.meta().dataMode());
            capacity += site.getCapacity();
            if (summary.entries() != null) {
                entries = (entries == null ? 0 : entries) + summary.entries();
            }
            if (summary.inParkCurrent() != null) {
                inPark = (inPark == null ? 0 : inPark) + summary.inParkCurrent();
            }
            if (summary.meta().dataAsOf() != null && (asOf == null || summary.meta().dataAsOf().isBefore(asOf))) {
                asOf = summary.meta().dataAsOf();
            }
            if ("STALE".equals(summary.meta().quality())) {
                stale = true;
                alerts.add(new Alert("WARN", "DATA_STALE", id, site.getShortName() + "客流数据已过期",
                        "最近一次数据时间 " + summary.meta().dataAsOf() + ", 请检查采集链路。", now, "flow"));
            }
            if ("FULL".equals(summary.loadLevel()) || "WARN".equals(summary.loadLevel())) {
                alerts.add(new Alert("FULL".equals(summary.loadLevel()) ? "CRITICAL" : "WARN", "ZONE_LOAD", id,
                        site.getShortName() + "在园人数" + ("FULL".equals(summary.loadLevel()) ? "已达最大承载量" : "超过预警线"),
                        "当前在园 %d 人, 承载量 %d 人。".formatted(summary.inParkCurrent(), site.getCapacity()), now, "site:" + id));
            }

            List<FlowQueryService.ZoneLoad> loads = flows.zoneLoads(id);
            int warn = 0;
            int full = 0;
            for (FlowQueryService.ZoneLoad z : loads) {
                if ("WARN".equals(z.level())) {
                    warn++;
                } else if ("FULL".equals(z.level())) {
                    full++;
                }
                if ("WARN".equals(z.level()) || "FULL".equals(z.level())) {
                    alerts.add(new Alert("FULL".equals(z.level()) ? "CRITICAL" : "WARN", "ZONE_LOAD", id,
                            site.getShortName() + "·" + z.zoneName() + ("FULL".equals(z.level()) ? "已饱和" : "负荷预警"),
                            "区域内 %d 人, 容量 %d 人(%.0f%%)。".formatted(z.count(), z.capacity(), z.ratio().doubleValue() * 100),
                            z.at(), "zone:" + z.zoneId()));
                }
            }

            int[] dev = jdbc.sql("""
                    SELECT count(*) FILTER (WHERE status = 'ONLINE'), count(*), count(*) FILTER (WHERE status IN ('OFFLINE', 'FAULT'))
                    FROM device WHERE site_id = ?
                    """).param(id).query((rs, i) -> new int[]{rs.getInt(1), rs.getInt(2), rs.getInt(3)}).single();
            jdbc.sql("SELECT id, name, status, status_at FROM device WHERE site_id = ? AND key_point AND status IN ('OFFLINE', 'FAULT') ORDER BY id")
                    .param(id).query((rs, i) -> new Alert("WARN", "DEVICE_OFFLINE", id,
                            site.getShortName() + "重点点位设备" + ("FAULT".equals(rs.getString("status")) ? "故障" : "离线"),
                            rs.getString("name"), FlowQueryServiceImpl.ts(rs, "status_at"), "device:" + rs.getLong("id")))
                    .list().forEach(alerts::add);

            ReviewCount reviews = jdbc.sql("""
                    SELECT count(*)::int AS total, count(*) FILTER (WHERE a.sentiment = 'NEGATIVE')::int AS negative,
                           round(avg(r.rating), 2) AS rating
                    FROM review r LEFT JOIN review_analysis a ON a.review_id = r.id AND a.is_current
                    WHERE r.site_id = ? AND r.published_at >= ? AND r.published_at < ?
                    """).params(id, dayStart, now).query((rs, i) -> new ReviewCount(rs.getInt("total"), rs.getInt("negative"), rs.getBigDecimal("rating"))).single();
            if (reviews.today() >= 5 && reviews.negative() * 3 >= reviews.today()) {
                alerts.add(new Alert("WARN", "REVIEW", id, site.getShortName() + "今日负面评价占比偏高",
                        "今日 %d 条评价中 %d 条为负面。".formatted(reviews.today(), reviews.negative()), now, "reviews:" + id));
            }

            InspectionCount inspections = jdbc.sql("""
                    SELECT count(*) FILTER (WHERE status NOT IN ('COMPLETED', 'CANCELLED'))::int AS open,
                           count(*) FILTER (WHERE status NOT IN ('COMPLETED', 'CANCELLED') AND due_at < ?)::int AS overdue,
                           (SELECT count(*)::int FROM inspection_issue i JOIN inspection_task t2 ON t2.id = i.task_id
                            WHERE t2.site_id = ? AND i.status <> 'CLOSED') AS issues
                    FROM inspection_task WHERE site_id = ?
                    """).params(now, id, id).query((rs, i) -> new InspectionCount(rs.getInt("open"), rs.getInt("overdue"), rs.getInt("issues"))).single();
            if (inspections.overdue() > 0) {
                alerts.add(new Alert("WARN", "INSPECTION", id, site.getShortName() + "有巡检任务逾期",
                        inspections.overdue() + " 项巡检任务已超过截止时间。", now, "inspections:" + id));
            }

            List<OpsInsightService.QueueStatus> queueStatus = insight.queues(id);
            List<QueueBrief> queues = queueStatus.stream()
                    .map(q -> new QueueBrief(q.name(), q.measuredWaitMin() != null ? q.measuredWaitMin() : q.estimatedWaitMin()))
                    .sorted(Comparator.comparing(QueueBrief::waitMin, Comparator.nullsLast(Comparator.reverseOrder()))).limit(3).toList();
            for (OpsInsightService.QueueStatus q : queueStatus) {
                BigDecimal wait = q.measuredWaitMin() != null ? q.measuredWaitMin() : q.estimatedWaitMin();
                if (wait != null && wait.doubleValue() >= 45 && "GOOD".equals(q.meta().quality())) {
                    alerts.add(new Alert("WARN", "QUEUE", id, site.getShortName() + "·" + q.name() + "排队时间较长",
                            "当前等待约 " + wait.setScale(0, java.math.RoundingMode.HALF_UP) + " 分钟"
                                    + (q.measuredWaitMin() != null ? "(实测)" : "(估计)") + "。", q.at(), "queue:" + q.pointId()));
                }
            }

            GroupCount groups = jdbc.sql("""
                    SELECT count(*)::int, coalesce(sum(CASE WHEN status IN ('ENTERED', 'LEFT') THEN entered_count ELSE planned_size END), 0)::int
                    FROM tour_group WHERE site_id = ? AND visit_date = ? AND status <> 'CANCELLED'
                    """).params(id, today).query((rs, i) -> new GroupCount(rs.getInt(1), rs.getInt(2))).single();

            sites.add(new OverviewSite(id, site.getName(), site.getShortName(), site.getStatus(), site.getCapacity(), site.getCenter(),
                    summary, new ZoneCount(warn, full, loads.size()), new DeviceCount(dev[0], dev[1], dev[2]), reviews, inspections,
                    queues, groups));
        }
        alerts.sort(Comparator.comparingInt((Alert a) -> "CRITICAL".equals(a.level()) ? 0 : "WARN".equals(a.level()) ? 1 : 2));
        String quality = asOf == null ? "MISSING" : stale ? "STALE" : "GOOD";
        return new Overview(now, sites, new Totals(entries, inPark, capacity), alerts,
                new DataMeta(asOf, sources.stream().sorted().toList(), modes.isEmpty() ? "DEMO" : DataMeta.mergeMode(List.copyOf(modes)), quality));
    }
}
