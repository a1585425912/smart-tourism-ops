package com.snowworld.flow;

import com.snowworld.common.ApiException;
import com.snowworld.common.ClockConfig;
import com.snowworld.common.DataMeta;
import com.snowworld.common.ErrorCode;
import com.snowworld.common.TimeRange;
import com.snowworld.scenic.SiteMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class FlowQueryServiceImpl implements FlowQueryService {

    /** 超过该时长没有新数据即视为过期(演示与默认采集周期为 10 分钟, 取 3 倍)。 */
    static final Duration STALE_AFTER = Duration.ofMinutes(30);
    private static final Set<String> TREND_METRICS = Set.of("ENTRY", "EXIT", "IN_PARK", "ZONE_COUNT");

    private final JdbcClient jdbc;
    private final SiteMapper siteMapper;
    private final Clock clock;

    public FlowQueryServiceImpl(JdbcClient jdbc, SiteMapper siteMapper, Clock clock) {
        this.jdbc = jdbc;
        this.siteMapper = siteMapper;
        this.clock = clock;
    }

    static OffsetDateTime ts(ResultSet rs, String column) throws SQLException {
        OffsetDateTime v = rs.getObject(column, OffsetDateTime.class);
        return v == null ? null : v.atZoneSameInstant(ClockConfig.BIZ_ZONE).toOffsetDateTime();
    }

    @Override
    public FlowSummary summary(long siteId, TimeRange range) {
        range.limit(Duration.ofDays(400));
        SiteMapper.SiteRow site = requireSite(siteId);
        OffsetDateTime now = OffsetDateTime.now(clock);
        // 区间尚未结束时, 对比口径只取已发生的部分, 避免"半天对比整天"
        OffsetDateTime effectiveEnd = range.end().isAfter(now) ? now : range.end();
        List<String> notes = new ArrayList<>();
        notes.add("入园/出园为闸机计次人次, 未去重, 不等同于独立游客数");

        Long entries = sum(siteId, "ENTRY", range.start(), range.end());
        Long exits = sum(siteId, "EXIT", range.start(), range.end());

        record Stock(Long value, OffsetDateTime at) {
        }
        Stock current = jdbc.sql("""
                SELECT value, biz_time FROM flow_event WHERE site_id = ? AND metric = 'IN_PARK' AND zone_id IS NULL
                  AND biz_time >= ? AND biz_time < ? ORDER BY biz_time DESC LIMIT 1
                """).params(siteId, range.start(), range.end())
                .query((rs, i) -> new Stock(rs.getBigDecimal("value").longValue(), ts(rs, "biz_time")))
                .optional().orElse(new Stock(null, null));
        Stock peak = jdbc.sql("""
                SELECT value, biz_time FROM flow_event WHERE site_id = ? AND metric = 'IN_PARK' AND zone_id IS NULL
                  AND biz_time >= ? AND biz_time < ? ORDER BY value DESC, biz_time LIMIT 1
                """).params(siteId, range.start(), range.end())
                .query((rs, i) -> new Stock(rs.getBigDecimal("value").longValue(), ts(rs, "biz_time")))
                .optional().orElse(new Stock(null, null));

        BigDecimal utilization = null;
        String loadLevel = "UNKNOWN";
        if (current.value() != null) {
            if (current.value() < 0) {
                notes.add("在园人数出现负值, 来源数据存在计数偏差, 未做修正");
            }
            utilization = BigDecimal.valueOf(current.value()).divide(BigDecimal.valueOf(site.getCapacity()), 4, RoundingMode.HALF_UP);
            loadLevel = utilization.compareTo(BigDecimal.ONE) >= 0 ? "FULL"
                    : utilization.compareTo(site.getWarnRatio()) >= 0 ? "WARN" : "NORMAL";
        }

        BigDecimal yoy = null;
        BigDecimal dod = null;
        if (entries != null && effectiveEnd.isAfter(range.start())) {
            Long sofar = sum(siteId, "ENTRY", range.start(), effectiveEnd);
            Long lastYear = sum(siteId, "ENTRY", range.start().minusYears(1), effectiveEnd.minusYears(1));
            yoy = ratio(sofar, lastYear);
            if (yoy == null) {
                notes.add("同比不可计算: 去年同期(公历同日)无入园数据");
            }
            Duration len = Duration.between(range.start(), range.end());
            Long previous = sum(siteId, "ENTRY", range.start().minus(len), effectiveEnd.minus(len));
            dod = ratio(sofar, previous);
            if (dod == null) {
                notes.add("环比不可计算: 上一周期无入园数据");
            }
        }
        if (entries == null) {
            notes.add("所选时间段内没有入园数据");
        }
        return new FlowSummary(siteId, site.getName(), range.start(), range.end(), entries, exits, current.value(),
                peak.value(), peak.at(), site.getCapacity(), utilization, loadLevel, yoy, dod, notes,
                meta(site, range, now));
    }

    private static BigDecimal ratio(Long value, Long base) {
        if (value == null || base == null || base == 0) {
            return null;
        }
        return BigDecimal.valueOf(value - base).divide(BigDecimal.valueOf(base), 4, RoundingMode.HALF_UP);
    }

    /** 没有任何记录时返回 null 而不是 0。 */
    private Long sum(long siteId, String metric, OffsetDateTime start, OffsetDateTime end) {
        BigDecimal v = jdbc.sql("""
                SELECT sum(value) FROM flow_event WHERE site_id = ? AND metric = ? AND zone_id IS NULL
                  AND biz_time >= ? AND biz_time < ?
                """).params(siteId, metric, start, end).query(BigDecimal.class).optional().orElse(null);
        return v == null ? null : v.longValue();
    }

    @Override
    public FlowTrend trend(long siteId, Long zoneId, String metric, String granularity, TimeRange range) {
        if (!TREND_METRICS.contains(metric)) {
            throw ApiException.badRequest("metric 只支持 " + TREND_METRICS);
        }
        if (!Set.of("HOUR", "DAY").contains(granularity)) {
            throw ApiException.badRequest("granularity 只支持 HOUR 或 DAY");
        }
        range.limit("HOUR".equals(granularity) ? Duration.ofDays(31) : Duration.ofDays(400));
        if ("ZONE_COUNT".equals(metric) != (zoneId != null)) {
            throw ApiException.badRequest("ZONE_COUNT 需要 zoneId, 其它指标不接受 zoneId");
        }
        SiteMapper.SiteRow site = requireSite(siteId);
        if (zoneId != null) {
            Long owner = jdbc.sql("SELECT site_id FROM scenic_zone WHERE id = ?").param(zoneId).query(Long.class)
                    .optional().orElse(null);
            if (owner == null || owner != siteId) {
                throw new ApiException(ErrorCode.NOT_FOUND, "区域不存在或不属于该景区");
            }
        }
        boolean stock = "IN_PARK".equals(metric) || "ZONE_COUNT".equals(metric);
        String aggregation = !stock ? "sum" : "HOUR".equals(granularity) ? "last" : "max";
        String column = switch (aggregation) {
            case "sum" -> "sum_value";
            case "last" -> "last_value";
            default -> "max_value";
        };
        Set<String> modes = new HashSet<>();
        List<TrendPoint> points = jdbc.sql("SELECT bucket_start, " + column + " AS v, data_mode FROM flow_aggregate "
                        + "WHERE site_id = ? AND zone_id = ? AND metric = ? AND granularity = ? AND bucket_start >= ? AND bucket_start < ? "
                        + "ORDER BY bucket_start")
                .params(siteId, zoneId == null ? 0L : zoneId, metric, granularity, range.start(), range.end())
                .query((rs, i) -> {
                    modes.add(rs.getString("data_mode"));
                    return new TrendPoint(ts(rs, "bucket_start"), rs.getBigDecimal("v"));
                }).list();

        OffsetDateTime now = OffsetDateTime.now(clock);
        List<Gap> gaps = "HOUR".equals(granularity) ? hourGaps(site, points, range, now) : dayGaps(points);
        OffsetDateTime asOf = points.isEmpty() ? null : dataAsOf(siteId, range.end());
        String quality = points.isEmpty() ? "MISSING" : !gaps.isEmpty() ? "PARTIAL" : staleness(site, asOf, range, now);
        String mode = modes.isEmpty() ? sourceMode(siteId) : DataMeta.mergeMode(List.copyOf(modes));
        return new FlowTrend(siteId, zoneId, metric, granularity, aggregation, points, gaps,
                new DataMeta(asOf, sourceCodes(siteId), mode, quality));
    }

    /** 小时粒度: 开放时段内、截至当前应有而没有的时间桶合并为缺失区间。 */
    private static List<Gap> hourGaps(SiteMapper.SiteRow site, List<TrendPoint> points, TimeRange range, OffsetDateTime now) {
        Set<OffsetDateTime> present = new HashSet<>();
        points.forEach(p -> present.add(p.t().toInstant().atOffset(now.getOffset())));
        LocalTime open = site.getOpenTime() == null ? LocalTime.MIN : site.getOpenTime();
        LocalTime close = site.getCloseTime() == null ? LocalTime.MAX : site.getCloseTime();
        List<Gap> gaps = new ArrayList<>();
        OffsetDateTime cursor = range.start().withOffsetSameInstant(now.getOffset()).truncatedTo(ChronoUnit.HOURS);
        OffsetDateTime limit = range.end().isAfter(now) ? now.truncatedTo(ChronoUnit.HOURS) : range.end();
        OffsetDateTime gapStart = null;
        for (; cursor.isBefore(limit); cursor = cursor.plusHours(1)) {
            LocalTime t = cursor.toLocalTime();
            boolean expected = !t.isBefore(open.truncatedTo(ChronoUnit.HOURS)) && t.isBefore(close);
            if (expected && !present.contains(cursor)) {
                if (gapStart == null) {
                    gapStart = cursor;
                }
            } else if (gapStart != null) {
                gaps.add(new Gap(gapStart, cursor));
                gapStart = null;
            }
        }
        if (gapStart != null) {
            gaps.add(new Gap(gapStart, limit));
        }
        return gaps;
    }

    /** 日粒度: 首尾数据之间缺失的日期(季节性闭园期同样如实列出)。 */
    private static List<Gap> dayGaps(List<TrendPoint> points) {
        List<Gap> gaps = new ArrayList<>();
        for (int i = 1; i < points.size(); i++) {
            OffsetDateTime prev = points.get(i - 1).t();
            OffsetDateTime cur = points.get(i).t();
            if (Duration.between(prev, cur).toHours() > 25) {
                gaps.add(new Gap(prev.plusDays(1), cur));
            }
        }
        return gaps;
    }

    @Override
    public List<ZoneLoad> zoneLoads(long siteId) {
        requireSite(siteId);
        OffsetDateTime now = OffsetDateTime.now(clock);
        return jdbc.sql("""
                SELECT z.id, z.name, z.zone_type, z.capacity, z.warn_ratio, z.count_source,
                       e.value, e.biz_time, e.data_mode, src.code AS source_code
                FROM scenic_zone z
                LEFT JOIN LATERAL (
                    SELECT value, biz_time, data_mode, source_id FROM flow_event
                    WHERE zone_id = z.id AND metric = 'ZONE_COUNT' AND biz_time <= ?
                    ORDER BY biz_time DESC LIMIT 1) e ON TRUE
                LEFT JOIN integration_source src ON src.id = e.source_id
                WHERE z.site_id = ? AND z.active
                ORDER BY z.id
                """).params(now, siteId).query((rs, i) -> {
            BigDecimal value = rs.getBigDecimal("value");
            Integer capacity = (Integer) rs.getObject("capacity");
            OffsetDateTime at = ts(rs, "biz_time");
            BigDecimal ratio = value == null || capacity == null ? null
                    : value.divide(BigDecimal.valueOf(capacity), 4, RoundingMode.HALF_UP);
            String level = ratio == null ? "UNKNOWN"
                    : ratio.compareTo(new BigDecimal("0.3")) < 0 ? "LOW"
                    : ratio.compareTo(rs.getBigDecimal("warn_ratio")) < 0 ? "NORMAL"
                    : ratio.compareTo(BigDecimal.ONE) < 0 ? "WARN" : "FULL";
            String source = rs.getString("source_code");
            String quality = value == null ? "MISSING"
                    : Duration.between(at, now).compareTo(STALE_AFTER) > 0 ? "STALE" : "GOOD";
            String mode = rs.getString("data_mode");
            return new ZoneLoad(rs.getLong("id"), rs.getString("name"), rs.getString("zone_type"),
                    value == null ? null : value.longValue(), capacity, ratio, level, at, rs.getString("count_source"),
                    new DataMeta(at, source == null ? List.of() : List.of(source), mode == null ? sourceMode(siteId) : mode, quality));
        }).list();
    }

    private SiteMapper.SiteRow requireSite(long siteId) {
        SiteMapper.SiteRow site = siteMapper.findById(siteId);
        if (site == null) {
            throw ApiException.notFound("景区");
        }
        return site;
    }

    private DataMeta meta(SiteMapper.SiteRow site, TimeRange range, OffsetDateTime now) {
        OffsetDateTime asOf = dataAsOf(site.getId(), range.end());
        if (asOf != null && asOf.isBefore(range.start())) {
            asOf = null;
        }
        String quality = asOf == null ? "MISSING" : staleness(site, asOf, range, now);
        List<String> modes = jdbc.sql("""
                SELECT DISTINCT data_mode FROM flow_aggregate WHERE site_id = ? AND zone_id = 0 AND granularity = 'DAY'
                  AND bucket_start >= ? AND bucket_start < ?
                """).params(site.getId(), range.start().minusDays(1), range.end()).query(String.class).list();
        return new DataMeta(asOf, sourceCodes(site.getId()), modes.isEmpty() ? sourceMode(site.getId()) : DataMeta.mergeMode(modes), quality);
    }

    /** 仅当查询区间覆盖"当前"且处于开放时段时才判断过期。 */
    private static String staleness(SiteMapper.SiteRow site, OffsetDateTime asOf, TimeRange range, OffsetDateTime now) {
        if (asOf == null) {
            return "MISSING";
        }
        boolean live = !range.end().isBefore(now) && !range.start().isAfter(now);
        if (!live) {
            return "GOOD";
        }
        LocalTime t = now.toLocalTime();
        boolean open = (site.getOpenTime() == null || !t.isBefore(site.getOpenTime()))
                && (site.getCloseTime() == null || t.isBefore(site.getCloseTime()));
        return open && Duration.between(asOf, now).compareTo(STALE_AFTER) > 0 ? "STALE" : "GOOD";
    }

    private OffsetDateTime dataAsOf(long siteId, OffsetDateTime before) {
        return jdbc.sql("""
                SELECT biz_time FROM flow_event WHERE site_id = ? AND metric = 'IN_PARK' AND biz_time < ?
                ORDER BY biz_time DESC LIMIT 1
                """).params(siteId, before).query((rs, i) -> ts(rs, "biz_time")).optional().orElse(null);
    }

    List<String> sourceCodes(long siteId) {
        return jdbc.sql("""
                SELECT code FROM integration_source WHERE source_type = 'FLOW' AND site_id = ? AND enabled
                  AND watermark IS NOT NULL ORDER BY code
                """).param(siteId).query(String.class).list();
    }

    String sourceMode(long siteId) {
        List<String> modes = jdbc.sql("SELECT DISTINCT data_mode FROM integration_source WHERE source_type = 'FLOW' AND site_id = ? AND enabled")
                .param(siteId).query(String.class).list();
        return DataMeta.mergeMode(modes);
    }

    static LocalDate bizDate(OffsetDateTime t) {
        return t.atZoneSameInstant(ClockConfig.BIZ_ZONE).toLocalDate();
    }
}
