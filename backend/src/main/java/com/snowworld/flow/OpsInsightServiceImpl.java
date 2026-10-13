package com.snowworld.flow;

import com.snowworld.common.ApiException;
import com.snowworld.common.ClockConfig;
import com.snowworld.common.DataMeta;
import com.snowworld.common.PageResult;
import com.snowworld.forecast.ForecastService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class OpsInsightServiceImpl implements OpsInsightService {

    private final JdbcClient jdbc;
    private final ForecastService forecast;
    private final Clock clock;

    public OpsInsightServiceImpl(JdbcClient jdbc, ForecastService forecast, Clock clock) {
        this.jdbc = jdbc;
        this.forecast = forecast;
        this.clock = clock;
    }

    // ---------- 排队 ----------

    @Override
    public List<QueueStatus> queues(long siteId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        // 未来几小时的等待时间按"预测在园人数相对当前小时的比例"推算
        List<HourlyPoint> hourly;
        try {
            hourly = forecast.hourly(siteId, now.toLocalDate()).points();
        } catch (ApiException e) {
            hourly = List.of();
        }
        BigDecimal base = null;
        for (HourlyPoint p : hourly) {
            if (p.t().getHour() == now.getHour()) {
                base = p.predictedInPark();
            }
        }
        List<HourlyPoint> future = hourly.stream().filter(p -> p.t().isAfter(now) && p.t().isBefore(now.plusHours(4))).toList();
        BigDecimal baseInPark = base;

        return jdbc.sql("""
                SELECT q.id, q.name, q.point_type, q.site_id, q.zone_id, ST_X(q.location) AS lon, ST_Y(q.location) AS lat,
                       q.service_rate_per_min, l.value AS len, l.biz_time AS len_at, l.data_mode, src.code AS source_code,
                       w.value AS wait, w.biz_time AS wait_at
                FROM queue_point q
                LEFT JOIN LATERAL (SELECT value, biz_time, data_mode, source_id FROM flow_event
                    WHERE site_id = q.site_id AND metric = 'QUEUE_LEN' AND device_id = q.device_id AND biz_time <= ?
                    ORDER BY biz_time DESC LIMIT 1) l ON TRUE
                LEFT JOIN LATERAL (SELECT value, biz_time FROM flow_event
                    WHERE site_id = q.site_id AND metric = 'QUEUE_WAIT' AND device_id = q.device_id AND biz_time <= ?
                    ORDER BY biz_time DESC LIMIT 1) w ON TRUE
                LEFT JOIN integration_source src ON src.id = l.source_id
                WHERE q.site_id = ? AND q.active ORDER BY q.id
                """).params(now, now, siteId).query((rs, i) -> {
            BigDecimal len = rs.getBigDecimal("len");
            BigDecimal rate = rs.getBigDecimal("service_rate_per_min");
            OffsetDateTime at = FlowQueryServiceImpl.ts(rs, "len_at");
            boolean stale = at != null && Duration.between(at, now).compareTo(FlowQueryServiceImpl.STALE_AFTER) > 0;
            BigDecimal estimated = len == null ? null : len.divide(rate, 1, RoundingMode.HALF_UP);
            OffsetDateTime waitAt = FlowQueryServiceImpl.ts(rs, "wait_at");
            // 实测值只在与排队人数同一时刻上报时才展示, 避免拿旧的实测值配新的人数
            BigDecimal measured = waitAt != null && waitAt.equals(at) ? rs.getBigDecimal("wait") : null;
            List<PredictedWait> predicted = new ArrayList<>();
            if (estimated != null && !stale && baseInPark != null && baseInPark.signum() > 0) {
                for (HourlyPoint p : future) {
                    predicted.add(new PredictedWait(p.t(), estimated.multiply(p.predictedInPark())
                            .divide(baseInPark, 1, RoundingMode.HALF_UP)));
                }
            }
            String source = rs.getString("source_code");
            String mode = rs.getString("data_mode");
            return new QueueStatus(rs.getLong("id"), rs.getString("name"), rs.getString("point_type"), rs.getLong("site_id"),
                    (Long) rs.getObject("zone_id"), rs.getDouble("lon"), rs.getDouble("lat"), len == null ? null : len.longValue(),
                    rate, estimated, measured,
                    "估计等待 = 排队人数 ÷ 服务能力(" + rate.stripTrailingZeros().toPlainString() + " 人/分钟)"
                            + (measured != null ? "; 实测值来自排队检测设备" : "; 该点位暂无实测值"),
                    at, predicted,
                    new DataMeta(at, source == null ? List.of() : List.of(source), mode == null ? "DEMO" : mode,
                            len == null ? "MISSING" : stale ? "STALE" : "GOOD"));
        }).list();
    }

    // ---------- 预测 ----------

    @Override
    public DailyForecast dailyForecast(long siteId, int days) {
        return forecast.daily(siteId, days);
    }

    @Override
    public HourlyForecast hourlyForecast(long siteId, LocalDate date) {
        return forecast.hourly(siteId, date);
    }

    // ---------- 多业态 ----------

    @Override
    public BusinessSummary businessSummary(long siteId, LocalDate start, LocalDate endExclusive) {
        checkDates(start, endExclusive, 400);
        Set<String> modes = new HashSet<>();
        Set<String> basis = new HashSet<>();
        LocalDate[] latest = {null};
        List<CategoryStat> byCategory = jdbc.sql("""
                SELECT u.category, sum(d.revenue) AS revenue, sum(d.orders) AS orders, sum(d.guests) AS guests,
                       count(DISTINCT u.id) AS units, round(avg(d.occupancy), 4) AS occupancy,
                       string_agg(DISTINCT d.data_mode, ',') AS modes, string_agg(DISTINCT d.amount_basis, ',') AS basis,
                       max(d.biz_date) AS latest
                FROM business_daily d JOIN business_unit u ON u.id = d.unit_id
                WHERE u.site_id = ? AND d.biz_date >= ? AND d.biz_date < ?
                GROUP BY u.category ORDER BY sum(d.revenue) DESC NULLS LAST
                """).params(siteId, start, endExclusive).query((rs, i) -> {
            modes.addAll(List.of(rs.getString("modes").split(",")));
            basis.addAll(List.of(rs.getString("basis").split(",")));
            LocalDate d = rs.getObject("latest", LocalDate.class);
            if (latest[0] == null || d.isAfter(latest[0])) {
                latest[0] = d;
            }
            return new CategoryStat(rs.getString("category"), rs.getBigDecimal("revenue"), (Long) rs.getObject("orders"),
                    (Long) rs.getObject("guests"), rs.getInt("units"), rs.getBigDecimal("occupancy"));
        }).list();
        List<DailyCategoryRevenue> daily = jdbc.sql("""
                SELECT d.biz_date, u.category, sum(d.revenue) AS revenue FROM business_daily d JOIN business_unit u ON u.id = d.unit_id
                WHERE u.site_id = ? AND d.biz_date >= ? AND d.biz_date < ? GROUP BY d.biz_date, u.category ORDER BY d.biz_date, u.category
                """).params(siteId, start, endExclusive)
                .query((rs, i) -> new DailyCategoryRevenue(rs.getObject("biz_date", LocalDate.class), rs.getString("category"), rs.getBigDecimal("revenue")))
                .list();
        List<UnitRevenue> top = jdbc.sql("""
                SELECT u.id, u.name, u.category, sum(d.revenue) AS revenue FROM business_daily d JOIN business_unit u ON u.id = d.unit_id
                WHERE u.site_id = ? AND d.biz_date >= ? AND d.biz_date < ? GROUP BY u.id, u.name, u.category
                ORDER BY sum(d.revenue) DESC NULLS LAST LIMIT 10
                """).params(siteId, start, endExclusive)
                .query((rs, i) -> new UnitRevenue(rs.getLong("id"), rs.getString("name"), rs.getString("category"), rs.getBigDecimal("revenue")))
                .list();
        String basisText = basis.isEmpty() ? "PAID" : String.join("/", basis);
        return new BusinessSummary(siteId, start, endExclusive, "CNY",
                basisText + "(按支付金额统计, 未扣除退款; 酒店入住率为各门店日入住率的简单平均)", byCategory, daily, top,
                dateMeta(latest[0], "business-demo", modes));
    }

    public List<Map<String, Object>> businessUnits(long siteId, String category) {
        return jdbc.sql("""
                SELECT id, site_id, code, name, category, ST_X(location) AS lon, ST_Y(location) AS lat, capacity, data_mode
                FROM business_unit WHERE site_id = ? AND (?::text IS NULL OR category = ?) ORDER BY category, id
                """).params(siteId, category, category).query((rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("siteId", rs.getLong("site_id"));
            m.put("code", rs.getString("code"));
            m.put("name", rs.getString("name"));
            m.put("category", rs.getString("category"));
            m.put("lon", rs.getObject("lon"));
            m.put("lat", rs.getObject("lat"));
            m.put("capacity", rs.getObject("capacity"));
            m.put("dataMode", rs.getString("data_mode"));
            return m;
        }).list();
    }

    public record LinkagePoint(LocalDate date, BigDecimal entries, BigDecimal hotelOccupancy, BigDecimal cateringRevenue,
                               BigDecimal retailRevenue) {
    }

    public record Correlation(String pair, BigDecimal r, int n) {
    }

    public record BusinessLinkage(long siteId, List<LinkagePoint> points, List<Correlation> correlations,
                                  List<String> insights, DataMeta meta) {
    }

    /** 客流与业态经营的联动: 逐日对齐后计算皮尔逊相关系数。相关不代表因果, 结论仅供运营参考。 */
    public BusinessLinkage linkage(long siteId, int days) {
        if (days < 7 || days > 180) {
            throw ApiException.badRequest("days 取值范围 7-180");
        }
        LocalDate today = LocalDate.now(clock);
        Set<String> modes = new HashSet<>();
        List<LinkagePoint> points = jdbc.sql("""
                WITH days AS (SELECT generate_series(?::date, ?::date, '1 day')::date AS d),
                     f AS (SELECT (bucket_start AT TIME ZONE 'Asia/Shanghai')::date AS d, sum_value, data_mode FROM flow_aggregate
                           WHERE site_id = ? AND zone_id = 0 AND metric = 'ENTRY' AND granularity = 'DAY'),
                     b AS (SELECT d.biz_date AS d,
                                  avg(d.occupancy) FILTER (WHERE u.category = 'HOTEL') AS occ,
                                  sum(d.revenue) FILTER (WHERE u.category = 'CATERING') AS catering,
                                  sum(d.revenue) FILTER (WHERE u.category = 'RETAIL') AS retail,
                                  min(d.data_mode) AS data_mode
                           FROM business_daily d JOIN business_unit u ON u.id = d.unit_id WHERE u.site_id = ? GROUP BY d.biz_date)
                SELECT days.d, f.sum_value AS entries, round(b.occ, 4) AS occ, b.catering, b.retail, f.data_mode AS fm, b.data_mode AS bm
                FROM days LEFT JOIN f ON f.d = days.d LEFT JOIN b ON b.d = days.d ORDER BY days.d
                """).params(today.minusDays(days), today.minusDays(1), siteId, siteId).query((rs, i) -> {
            if (rs.getString("fm") != null) {
                modes.add(rs.getString("fm"));
            }
            if (rs.getString("bm") != null) {
                modes.add(rs.getString("bm"));
            }
            return new LinkagePoint(rs.getObject("d", LocalDate.class), rs.getBigDecimal("entries"), rs.getBigDecimal("occ"),
                    rs.getBigDecimal("catering"), rs.getBigDecimal("retail"));
        }).list();
        List<Correlation> correlations = List.of(
                correlation("入园人次 × 酒店入住率", points, LinkagePoint::hotelOccupancy),
                correlation("入园人次 × 餐饮营收", points, LinkagePoint::cateringRevenue),
                correlation("入园人次 × 零售营收", points, LinkagePoint::retailRevenue));
        List<String> insights = new ArrayList<>();
        for (Correlation c : correlations) {
            if (c.r() == null) {
                insights.add(c.pair() + ": 有效样本不足, 无法计算相关性");
            } else if (c.r().doubleValue() >= 0.6) {
                insights.add("%s 呈较强正相关(r=%s, n=%d), 可在客流高峰日前联动相关业态备货、排班或推出组合产品".formatted(c.pair(), c.r(), c.n()));
            } else if (c.r().doubleValue() >= 0.3) {
                insights.add("%s 呈中等正相关(r=%s, n=%d), 联动效果有待更多数据验证".formatted(c.pair(), c.r(), c.n()));
            } else {
                insights.add("%s 相关性较弱(r=%s, n=%d)".formatted(c.pair(), c.r(), c.n()));
            }
        }
        insights.add("相关性不代表因果关系; 当前业态数据为演示数据, 结论仅用于展示分析方法");
        return new BusinessLinkage(siteId, points, correlations, insights,
                dateMeta(points.isEmpty() ? null : today.minusDays(1), "business-demo", modes));
    }

    private static Correlation correlation(String pair, List<LinkagePoint> points, java.util.function.Function<LinkagePoint, BigDecimal> other) {
        List<double[]> xy = new ArrayList<>();
        for (LinkagePoint p : points) {
            if (p.entries() != null && other.apply(p) != null) {
                xy.add(new double[]{p.entries().doubleValue(), other.apply(p).doubleValue()});
            }
        }
        int n = xy.size();
        if (n < 7) {
            return new Correlation(pair, null, n);
        }
        double mx = xy.stream().mapToDouble(v -> v[0]).average().orElse(0);
        double my = xy.stream().mapToDouble(v -> v[1]).average().orElse(0);
        double sxy = 0;
        double sxx = 0;
        double syy = 0;
        for (double[] v : xy) {
            sxy += (v[0] - mx) * (v[1] - my);
            sxx += (v[0] - mx) * (v[0] - mx);
            syy += (v[1] - my) * (v[1] - my);
        }
        if (sxx == 0 || syy == 0) {
            return new Correlation(pair, null, n);
        }
        return new Correlation(pair, BigDecimal.valueOf(sxy / Math.sqrt(sxx * syy)).setScale(3, RoundingMode.HALF_UP), n);
    }

    // ---------- 旅行社与团队 ----------

    @Override
    public GroupStats groupStats(long siteId, LocalDate start, LocalDate endExclusive) {
        checkDates(start, endExclusive, 400);
        Set<String> modes = new HashSet<>(jdbc.sql("SELECT DISTINCT data_mode FROM tour_group WHERE site_id = ? AND visit_date >= ? AND visit_date < ?")
                .params(siteId, start, endExclusive).query(String.class).list());
        String base = " FROM tour_group g JOIN travel_agency a ON a.id = g.agency_id "
                + "WHERE g.site_id = ? AND g.visit_date >= ? AND g.visit_date < ? AND g.status <> 'CANCELLED' ";
        // 已入园的团队按实际入园人数计, 未入园的按预约人数计
        String visitors = "sum(CASE WHEN g.status IN ('ENTERED', 'LEFT') THEN g.entered_count ELSE g.planned_size END)::int";
        List<AgencyStat> byAgency = jdbc.sql("SELECT a.id, a.name, count(*)::int AS groups, " + visitors + " AS visitors" + base
                        + "GROUP BY a.id, a.name ORDER BY visitors DESC LIMIT 20").params(siteId, start, endExclusive)
                .query((rs, i) -> new AgencyStat(rs.getLong("id"), rs.getString("name"), rs.getInt("groups"), rs.getInt("visitors"))).list();
        List<OriginStat> byOrigin = jdbc.sql("SELECT coalesce(g.origin_region, '未知') AS region, count(*)::int AS groups, " + visitors + " AS visitors"
                        + base + "GROUP BY 1 ORDER BY visitors DESC").params(siteId, start, endExclusive)
                .query((rs, i) -> new OriginStat(rs.getString("region"), rs.getInt("groups"), rs.getInt("visitors"))).list();
        List<DailyGroupStat> daily = jdbc.sql("SELECT g.visit_date, count(*)::int AS groups, " + visitors + " AS visitors" + base
                        + "GROUP BY g.visit_date ORDER BY g.visit_date").params(siteId, start, endExclusive)
                .query((rs, i) -> new DailyGroupStat(rs.getObject("visit_date", LocalDate.class), rs.getInt("groups"), rs.getInt("visitors"))).list();
        int groups = daily.stream().mapToInt(DailyGroupStat::groups).sum();
        int total = daily.stream().mapToInt(DailyGroupStat::visitors).sum();
        return new GroupStats(groups, total, byAgency, byOrigin, daily,
                dateMeta(daily.isEmpty() ? null : LocalDate.now(clock), "group-demo", modes));
    }

    public PageResult<Map<String, Object>> groups(List<Long> siteIds, LocalDate date, Long agencyId, String status, int page, int size) {
        if (siteIds.isEmpty()) {
            return new PageResult<>(List.of(), 0, page, size);
        }
        String where = " FROM tour_group g JOIN travel_agency a ON a.id = g.agency_id JOIN scenic_site s ON s.id = g.site_id "
                + "WHERE g.site_id = ANY(?) AND (?::date IS NULL OR g.visit_date = ?) AND (?::bigint IS NULL OR g.agency_id = ?) "
                + "AND (?::text IS NULL OR g.status = ?) ";
        Long[] ids = siteIds.toArray(Long[]::new);
        long total = jdbc.sql("SELECT count(*)" + where).params(ids, date, date, agencyId, agencyId, status, status).query(Long.class).single();
        List<Map<String, Object>> items = jdbc.sql("""
                SELECT g.id, g.group_code, g.agency_id, a.name AS agency_name, g.site_id, s.name AS site_name, g.visit_date,
                       g.planned_size, g.entered_count, g.guide_name, g.guide_phone_masked, g.guide_cert_no, g.origin_region,
                       g.status, g.entered_at, g.data_mode
                """ + where + "ORDER BY g.visit_date DESC, g.id DESC LIMIT ? OFFSET ?")
                .params(ids, date, date, agencyId, agencyId, status, status, size, PageResult.offset(page, size)).query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("groupCode", rs.getString("group_code"));
                    m.put("agencyId", rs.getLong("agency_id"));
                    m.put("agencyName", rs.getString("agency_name"));
                    m.put("siteId", rs.getLong("site_id"));
                    m.put("siteName", rs.getString("site_name"));
                    m.put("visitDate", rs.getObject("visit_date", LocalDate.class));
                    m.put("plannedSize", rs.getInt("planned_size"));
                    m.put("enteredCount", rs.getInt("entered_count"));
                    m.put("guideName", rs.getString("guide_name"));
                    m.put("guidePhoneMasked", rs.getString("guide_phone_masked"));
                    m.put("guideCertNo", rs.getString("guide_cert_no"));
                    m.put("originRegion", rs.getString("origin_region"));
                    m.put("status", rs.getString("status"));
                    m.put("enteredAt", FlowQueryServiceImpl.ts(rs, "entered_at"));
                    m.put("dataMode", rs.getString("data_mode"));
                    return m;
                }).list();
        return new PageResult<>(items, total, page, size);
    }

    public List<Map<String, Object>> agencies() {
        return jdbc.sql("SELECT id, code, name, license_no, region, contact_masked, data_mode FROM travel_agency ORDER BY id")
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("code", rs.getString("code"));
                    m.put("name", rs.getString("name"));
                    m.put("licenseNo", rs.getString("license_no"));
                    m.put("region", rs.getString("region"));
                    m.put("contactMasked", rs.getString("contact_masked"));
                    m.put("dataMode", rs.getString("data_mode"));
                    return m;
                }).list();
    }

    // ---------- 游客画像 ----------

    @Override
    public VisitorProfile visitorProfile(long siteId, YearMonth month) {
        Map<String, List<Bucket>> dimensions = new LinkedHashMap<>();
        Set<String> modes = new HashSet<>();
        int[] sample = {0};
        BigDecimal[] coverage = {null};
        jdbc.sql("""
                SELECT dimension, bucket, visitors, sample_size, coverage, data_mode,
                       round(visitors::numeric / nullif(sum(visitors) OVER (PARTITION BY dimension), 0), 4) AS share
                FROM visitor_profile_stat WHERE site_id = ? AND stat_month = ? ORDER BY dimension, visitors DESC
                """).params(siteId, month.atDay(1)).query((rs, i) -> {
            dimensions.computeIfAbsent(rs.getString("dimension"), k -> new ArrayList<>())
                    .add(new Bucket(rs.getString("bucket"), rs.getInt("visitors"), rs.getBigDecimal("share")));
            sample[0] = Math.max(sample[0], rs.getInt("sample_size"));
            coverage[0] = rs.getBigDecimal("coverage");
            modes.add(rs.getString("data_mode"));
            return 0;
        }).list();
        List<String> notes = new ArrayList<>();
        if (dimensions.isEmpty()) {
            notes.add("该月份暂无画像数据");
        } else {
            notes.add("画像为抽样统计: 样本 %d 人, 覆盖率 %s%%, 未识别部分计入\"未知\", 不可直接外推为全量游客结构"
                    .formatted(sample[0], coverage[0].movePointRight(2).stripTrailingZeros().toPlainString()));
            notes.add("画像来源为票务实名与渠道订单的聚合统计, 不含个人信息; 不使用网评样本推断游客结构");
        }
        return new VisitorProfile(siteId, month.toString(), sample[0], coverage[0], dimensions, notes,
                dateMeta(dimensions.isEmpty() ? null : month.atEndOfMonth(), "profile-demo", modes));
    }

    // ---------- 公共 ----------

    private static void checkDates(LocalDate start, LocalDate endExclusive, int maxDays) {
        if (start == null || endExclusive == null || !endExclusive.isAfter(start)) {
            throw ApiException.badRequest("end 必须晚于 start");
        }
        if (endExclusive.toEpochDay() - start.toEpochDay() > maxDays) {
            throw new ApiException(com.snowworld.common.ErrorCode.RANGE_TOO_LARGE, "日期范围不能超过 " + maxDays + " 天");
        }
    }

    private DataMeta dateMeta(LocalDate latest, String source, Set<String> modes) {
        if (latest == null) {
            return DataMeta.missing(List.of(source), modes.isEmpty() ? "DEMO" : DataMeta.mergeMode(List.copyOf(modes)));
        }
        OffsetDateTime asOf = latest.plusDays(1).atStartOfDay(ClockConfig.BIZ_ZONE).toOffsetDateTime();
        OffsetDateTime now = OffsetDateTime.now(clock);
        return new DataMeta(asOf.isAfter(now) ? now : asOf, List.of(source), DataMeta.mergeMode(List.copyOf(modes)), "GOOD");
    }
}
