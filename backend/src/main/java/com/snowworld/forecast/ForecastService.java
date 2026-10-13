package com.snowworld.forecast;

import com.snowworld.common.ApiException;
import com.snowworld.common.ClockConfig;
import com.snowworld.common.DataMeta;
import com.snowworld.flow.OpsInsightService.Backtest;
import com.snowworld.flow.OpsInsightService.DailyForecast;
import com.snowworld.flow.OpsInsightService.DailyPoint;
import com.snowworld.flow.OpsInsightService.ForecastModel;
import com.snowworld.flow.OpsInsightService.HourlyForecast;
import com.snowworld.flow.OpsInsightService.HourlyPoint;
import com.snowworld.flow.OpsInsightService.Peak;
import com.snowworld.flow.OpsInsightService.Recommendation;
import com.snowworld.flow.OpsInsightService.Weather;
import com.snowworld.flow.OpsInsightService.ZonePeak;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 客流预测: 以历史日入园量为目标, 用星期、节假日、月份、天气和年度趋势做对数线性岭回归。
 *
 * <p>评估采用时间顺序回测: 只用测试窗口之前的数据训练, 预测最近 28 个有数据的日期, 与"上周同日"基线比较,
 * 不使用任何未来信息。小时与区域预测由日预测值乘以近期各小时/区域占比得到, 属于规则推算。
 * 当前训练数据为仿真客流, 回测误差只说明流程可用, 不代表真实场景精度。
 */
@Service
public class ForecastService {

    public static final String MODEL_NAME = "ridge-loglinear";
    public static final String MODEL_VERSION = "v1";
    private static final String BASELINE = "上周同日(季节性朴素)";
    private static final int MIN_TRAIN_DAYS = 60;
    private static final int TEST_DAYS = 28;
    private static final int MAX_HORIZON = 14;
    private static final double RIDGE_LAMBDA = 0.5;
    /** 每多少名区域内游客建议配置 1 名现场引导人员(可调的演示规则)。 */
    private static final int VISITORS_PER_STAFF = 200;

    private static final List<String> FEATURES = List.of("星期", "法定节假日", "春节", "国庆", "调休上班", "月份",
            "平均气温", "极寒", "降雨", "降雪", "大风", "寒假", "年度趋势");

    private record Holiday(String name, boolean offDay) {
    }

    private record Fit(double[] coef, double sigma) {
    }

    private record Computed(LocalDate today, ForecastModel model, List<DailyPoint> points, Backtest backtest,
                            String dataMode, OffsetDateTime dataAsOf, List<Map<String, Object>> backtestDetail) {
    }

    private final JdbcClient jdbc;
    private final Clock clock;
    private final Map<Long, Computed> cache = new ConcurrentHashMap<>();
    private volatile Map<LocalDate, Holiday> holidays;

    public ForecastService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public DailyForecast daily(long siteId, int days) {
        if (days < 1 || days > MAX_HORIZON) {
            throw ApiException.badRequest("days 取值范围 1-" + MAX_HORIZON);
        }
        Computed c = compute(siteId);
        String siteName = jdbc.sql("SELECT name FROM scenic_site WHERE id = ?").param(siteId).query(String.class).single();
        LocalDate limit = c.today().plusDays(days);
        List<DailyPoint> points = c.points().stream().filter(p -> p.date().isBefore(limit)).toList();
        String quality = c.model() == null ? "MISSING" : "GOOD";
        return new DailyForecast(siteId, siteName, "ENTRY", c.model(), points, c.backtest(),
                new DataMeta(c.dataAsOf(), sources(siteId), c.dataMode(), quality));
    }

    public HourlyForecast hourly(long siteId, LocalDate date) {
        Computed c = compute(siteId);
        if (date.isBefore(c.today()) || !date.isBefore(c.today().plusDays(MAX_HORIZON))) {
            throw ApiException.badRequest("只能查看今天起 " + MAX_HORIZON + " 天内的小时预测");
        }
        DailyPoint day = c.points().stream().filter(p -> p.date().equals(date)).findFirst().orElse(null);
        DataMeta meta = new DataMeta(c.dataAsOf(), sources(siteId), c.dataMode(), day == null ? "MISSING" : "GOOD");
        if (day == null || day.predicted() == null) {
            return new HourlyForecast(siteId, date, List.of(), null, List.of(), List.of(), meta);
        }
        double daily = day.predicted().doubleValue();
        OffsetDateTime todayStart = c.today().atStartOfDay(ClockConfig.BIZ_ZONE).toOffsetDateTime();
        OffsetDateTime dayStart = date.atStartOfDay(ClockConfig.BIZ_ZONE).toOffsetDateTime();

        List<HourlyPoint> points = new ArrayList<>();
        Map<Integer, Double> inParkByHour = new HashMap<>();
        jdbc.sql("""
                WITH d AS (SELECT bucket_start, sum_value FROM flow_aggregate
                           WHERE site_id = ? AND zone_id = 0 AND metric = 'ENTRY' AND granularity = 'DAY'
                             AND bucket_start >= ? AND bucket_start < ? AND sum_value > 0),
                     h AS (SELECT date_trunc('day', bucket_start, 'Asia/Shanghai') AS day,
                                  extract(hour FROM bucket_start AT TIME ZONE 'Asia/Shanghai')::int AS hr, metric, sum_value, last_value
                           FROM flow_aggregate WHERE site_id = ? AND zone_id = 0 AND granularity = 'HOUR'
                             AND metric IN ('ENTRY', 'IN_PARK') AND bucket_start >= ? AND bucket_start < ?)
                SELECT h.hr, avg(CASE WHEN h.metric = 'ENTRY' THEN h.sum_value / d.sum_value END) AS entry_share,
                       avg(CASE WHEN h.metric = 'IN_PARK' THEN h.last_value / d.sum_value END) AS stock_share
                FROM h JOIN d ON d.bucket_start = h.day GROUP BY h.hr ORDER BY h.hr
                """).params(siteId, todayStart.minusDays(28), todayStart, siteId, todayStart.minusDays(28), todayStart)
                .query((rs, i) -> {
                    int hr = rs.getInt("hr");
                    double inPark = daily * rs.getDouble("stock_share");
                    inParkByHour.put(hr, inPark);
                    return new HourlyPoint(dayStart.plusHours(hr), round(inPark), round(daily * rs.getDouble("entry_share")));
                }).list().forEach(points::add);
        if (points.isEmpty()) {
            return new HourlyForecast(siteId, date, List.of(), null, List.of(), List.of(), meta);
        }
        HourlyPoint top = points.stream().max(Comparator.comparing(HourlyPoint::predictedInPark)).orElseThrow();
        Peak peak = new Peak(top.t(), top.predictedInPark());

        // 区域峰值 = 各小时(预测在园 × 该区域近 14 天同小时占在园人数的平均比例)的最大值
        Map<Long, double[]> zonePeak = new HashMap<>();
        jdbc.sql("""
                SELECT z.zone_id, extract(hour FROM z.bucket_start AT TIME ZONE 'Asia/Shanghai')::int AS hr,
                       avg(z.last_value / nullif(s.last_value, 0)) AS share
                FROM flow_aggregate z JOIN flow_aggregate s ON s.site_id = z.site_id AND s.zone_id = 0 AND s.metric = 'IN_PARK'
                     AND s.granularity = 'HOUR' AND s.bucket_start = z.bucket_start
                WHERE z.site_id = ? AND z.metric = 'ZONE_COUNT' AND z.granularity = 'HOUR' AND z.bucket_start >= ? AND z.bucket_start < ?
                GROUP BY z.zone_id, hr
                """).params(siteId, todayStart.minusDays(14), todayStart).query((rs, i) -> {
            int hr = rs.getInt("hr");
            double value = inParkByHour.getOrDefault(hr, 0.0) * rs.getDouble("share");
            double[] best = zonePeak.computeIfAbsent(rs.getLong("zone_id"), k -> new double[]{-1, 0});
            if (value > best[0]) {
                best[0] = value;
                best[1] = hr;
            }
            return 0;
        }).list();

        List<ZonePeak> zonePeaks = new ArrayList<>();
        List<Recommendation> recommendations = new ArrayList<>();
        jdbc.sql("SELECT id, name, capacity, warn_ratio FROM scenic_zone WHERE site_id = ? AND active ORDER BY id").param(siteId)
                .query((rs, i) -> {
                    double[] best = zonePeak.get(rs.getLong("id"));
                    if (best == null || best[0] < 0) {
                        return null;
                    }
                    Integer capacity = (Integer) rs.getObject("capacity");
                    BigDecimal ratio = capacity == null ? null
                            : BigDecimal.valueOf(best[0] / capacity).setScale(4, RoundingMode.HALF_UP);
                    String level = ratio == null ? "UNKNOWN" : ratio.doubleValue() < 0.3 ? "LOW"
                            : ratio.compareTo(rs.getBigDecimal("warn_ratio")) < 0 ? "NORMAL" : ratio.doubleValue() < 1 ? "WARN" : "FULL";
                    return new ZonePeak(rs.getLong("id"), rs.getString("name"), capacity, round(best[0]),
                            dayStart.plusHours((long) best[1]), ratio, level, Math.max(1, (int) Math.ceil(best[0] / VISITORS_PER_STAFF)));
                }).list().stream().filter(z -> z != null).forEach(zonePeaks::add);

        Integer siteCapacity = jdbc.sql("SELECT capacity FROM scenic_site WHERE id = ?").param(siteId).query(Integer.class).single();
        double siteRatio = peak.value().doubleValue() / siteCapacity;
        if (siteRatio >= 1) {
            recommendations.add(new Recommendation("CRITICAL", "预计在园人数将超过最大承载量",
                    "预计 %s 在园约 %s 人, 达到承载量的 %.0f%%。建议启动分时预约与入口限流, 并提前发布客流提示。"
                            .formatted(hm(peak.t()), peak.value().toPlainString(), siteRatio * 100), null));
        } else if (siteRatio >= 0.8) {
            recommendations.add(new Recommendation("WARN", "预计在园人数接近承载预警线",
                    "预计 %s 在园约 %s 人, 为承载量的 %.0f%%。建议增开检票通道并安排机动引导人员待命。"
                            .formatted(hm(peak.t()), peak.value().toPlainString(), siteRatio * 100), null));
        }
        for (ZonePeak z : zonePeaks) {
            if ("FULL".equals(z.level())) {
                recommendations.add(new Recommendation("CRITICAL", z.zoneName() + "预计超出区域容量",
                        "预计 %s 达到约 %s 人(容量 %d)。建议实行单向通行或分批放行, 配置引导人员 %d 名, 并在入口屏幕引导分流。"
                                .formatted(hm(z.peakAt()), z.peak().toPlainString(), z.capacity(), z.staffSuggested()), z.zoneId()));
            } else if ("WARN".equals(z.level())) {
                recommendations.add(new Recommendation("WARN", z.zoneName() + "预计达到预警负荷",
                        "预计 %s 达到约 %s 人, 为容量的 %.0f%%。建议配置引导人员 %d 名并加密巡查。"
                                .formatted(hm(z.peakAt()), z.peak().toPlainString(), z.ratio().doubleValue() * 100, z.staffSuggested()), z.zoneId()));
            }
        }
        if (recommendations.isEmpty()) {
            recommendations.add(new Recommendation("INFO", "预计客流平稳", "各区域预测峰值均低于预警线, 按常规排班即可。", null));
        }
        recommendations.add(new Recommendation("INFO", "人员配置规则",
                "建议人数按区域峰值每 " + VISITORS_PER_STAFF + " 人配置 1 名引导人员估算, 属可调整的规则推算, 需结合现场情况确认。", null));
        return new HourlyForecast(siteId, date, points, peak, zonePeaks, recommendations, meta);
    }

    private static String hm(OffsetDateTime t) {
        return "%02d:00".formatted(t.getHour());
    }

    /** 重新训练并落库(预测批次与回测记录), 供任务与演示初始化调用。 */
    @Transactional
    public void refresh(long siteId) {
        cache.remove(siteId);
        holidays = null;
        Computed c = compute(siteId);
        if (c.model() == null) {
            return;
        }
        String batch = UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        OffsetDateTime now = OffsetDateTime.now(clock);
        for (DailyPoint p : c.points()) {
            if (p.actual() != null || p.predicted() == null) {
                continue;
            }
            jdbc.sql("""
                    INSERT INTO flow_forecast (site_id, zone_id, metric, granularity, target_start, predicted, lower_bound,
                        upper_bound, model_name, model_version, generated_at, batch_id)
                    VALUES (?, 0, 'ENTRY', 'DAY', ?, ?, ?, ?, ?, ?, ?, ?)
                    """).params(siteId, p.date().atStartOfDay(ClockConfig.BIZ_ZONE).toOffsetDateTime(), p.predicted(), p.lower(),
                    p.upper(), MODEL_NAME, MODEL_VERSION, now, batch).update();
        }
        Backtest b = c.backtest();
        if (b != null) {
            jdbc.sql("""
                    INSERT INTO forecast_backtest (site_id, model_name, model_version, train_end, test_start, test_end, mape, mae,
                        baseline_name, baseline_mape, baseline_mae, sample_days, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """).params(siteId, b.modelName(), b.modelVersion(), b.testStart().minusDays(1), b.testStart(), b.testEnd(),
                    b.mape(), b.mae(), b.baselineName(), b.baselineMape(), b.baselineMae(), b.sampleDays(), now).update();
        }
    }

    public List<Map<String, Object>> backtests(long siteId) {
        return jdbc.sql("""
                SELECT id, model_name, model_version, train_end, test_start, test_end, mape, mae, baseline_name, baseline_mape,
                       baseline_mae, sample_days, created_at FROM forecast_backtest WHERE site_id = ? ORDER BY id DESC LIMIT 20
                """).param(siteId).query((rs, i) -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("modelName", rs.getString("model_name"));
            m.put("modelVersion", rs.getString("model_version"));
            m.put("trainEnd", rs.getObject("train_end", LocalDate.class));
            m.put("testStart", rs.getObject("test_start", LocalDate.class));
            m.put("testEnd", rs.getObject("test_end", LocalDate.class));
            m.put("mape", rs.getBigDecimal("mape"));
            m.put("mae", rs.getBigDecimal("mae"));
            m.put("baselineName", rs.getString("baseline_name"));
            m.put("baselineMape", rs.getBigDecimal("baseline_mape"));
            m.put("baselineMae", rs.getBigDecimal("baseline_mae"));
            m.put("sampleDays", rs.getInt("sample_days"));
            m.put("createdAt", rs.getObject("created_at", OffsetDateTime.class));
            return m;
        }).list();
    }

    private Computed compute(long siteId) {
        LocalDate today = LocalDate.now(clock);
        Computed cached = cache.get(siteId);
        if (cached != null && cached.today().equals(today)) {
            return cached;
        }
        Computed fresh = doCompute(siteId, today);
        cache.put(siteId, fresh);
        return fresh;
    }

    private Computed doCompute(long siteId, LocalDate today) {
        OffsetDateTime todayStart = today.atStartOfDay(ClockConfig.BIZ_ZONE).toOffsetDateTime();
        List<LocalDate> dates = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        Set<String> modes = new HashSet<>();
        jdbc.sql("""
                SELECT (bucket_start AT TIME ZONE 'Asia/Shanghai')::date AS d, sum_value, data_mode FROM flow_aggregate
                WHERE site_id = ? AND zone_id = 0 AND metric = 'ENTRY' AND granularity = 'DAY' AND bucket_start < ? AND sum_value > 0
                ORDER BY bucket_start
                """).params(siteId, todayStart).query((rs, i) -> {
            dates.add(rs.getObject("d", LocalDate.class));
            values.add(rs.getDouble("sum_value"));
            modes.add(rs.getString("data_mode"));
            return 0;
        }).list();
        String mode = modes.isEmpty() ? "DEMO" : DataMeta.mergeMode(List.copyOf(modes));
        OffsetDateTime asOf = dates.isEmpty() ? null : dates.getLast().plusDays(1).atStartOfDay(ClockConfig.BIZ_ZONE).toOffsetDateTime();
        if (dates.size() < MIN_TRAIN_DAYS) {
            return new Computed(today, null, List.of(), null, mode, asOf, List.of());
        }
        Map<LocalDate, Weather> weather = loadWeather(dates.getFirst(), today.plusDays(MAX_HORIZON));
        int n = dates.size();
        double[][] x = new double[n][];
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = features(dates.get(i), weatherFor(weather, dates.get(i)));
            y[i] = Math.log(values.get(i));
        }

        // 时间顺序回测: 训练集严格早于测试窗口
        int testFrom = n - TEST_DAYS;
        Fit testFit = fit(x, y, testFrom);
        Map<LocalDate, Double> actualByDate = new HashMap<>();
        for (int i = 0; i < n; i++) {
            actualByDate.put(dates.get(i), values.get(i));
        }
        double ape = 0;
        double ae = 0;
        double bApe = 0;
        double bAe = 0;
        int bCount = 0;
        List<DailyPoint> points = new ArrayList<>();
        for (int i = testFrom; i < n; i++) {
            double pred = Math.exp(dot(testFit.coef(), x[i]));
            double actual = values.get(i);
            ape += Math.abs(pred - actual) / actual;
            ae += Math.abs(pred - actual);
            Double naive = actualByDate.get(dates.get(i).minusDays(7));
            if (naive != null) {
                bApe += Math.abs(naive - actual) / actual;
                bAe += Math.abs(naive - actual);
                bCount++;
            }
            if (i >= n - 14) {
                points.add(point(dates.get(i), pred, testFit, actual, weatherFor(weather, dates.get(i)), x[i]));
            }
        }
        Backtest backtest = new Backtest(MODEL_NAME, MODEL_VERSION, dates.get(testFrom), dates.get(n - 1), TEST_DAYS,
                dec(ape / TEST_DAYS, 4), dec(ae / TEST_DAYS, 0), BASELINE,
                bCount == 0 ? null : dec(bApe / bCount, 4), bCount == 0 ? null : dec(bAe / bCount, 0));

        Fit fit = fit(x, y, n);
        Fit finalFit = new Fit(fit.coef(), testFit.sigma());
        Set<MonthDay> openDays = new HashSet<>();
        dates.forEach(d -> openDays.add(MonthDay.from(d)));
        boolean seasonal = "SEASONAL".equals(jdbc.sql("SELECT status FROM scenic_site WHERE id = ?").param(siteId).query(String.class).single());
        for (int k = 0; k < MAX_HORIZON; k++) {
            LocalDate d = today.plusDays(k);
            Weather w = weatherFor(weather, d);
            if (seasonal && !nearOpenDay(openDays, d)) {
                points.add(new DailyPoint(d, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, holidayName(d), isOffDay(d), w,
                        List.of("闭园期: 往年同期无开放记录")));
                continue;
            }
            double[] f = features(d, w);
            points.add(point(d, Math.exp(dot(finalFit.coef(), f)), finalFit, null, w, f));
        }
        ForecastModel model = new ForecastModel(MODEL_NAME, MODEL_VERSION, OffsetDateTime.now(clock), FEATURES);
        return new Computed(today, model, points, backtest, mode, asOf, List.of());
    }

    private static boolean nearOpenDay(Set<MonthDay> openDays, LocalDate d) {
        for (int k = -3; k <= 3; k++) {
            if (openDays.contains(MonthDay.from(d.plusDays(k)))) {
                return true;
            }
        }
        return false;
    }

    private DailyPoint point(LocalDate date, double predicted, Fit fit, Double actual, Weather weather, double[] f) {
        // 80% 预测区间: 对数空间训练残差标准差 × 1.28
        double spread = 1.28 * fit.sigma();
        return new DailyPoint(date, round(predicted), round(predicted * Math.exp(-spread)), round(predicted * Math.exp(spread)),
                actual == null ? null : round(actual), holidayName(date), isOffDay(date), weather, drivers(fit.coef(), f, weather));
    }

    // ---- 特征 ----
    private static final int F_BIAS = 0, F_DOW = 1, F_OFF = 7, F_SPRING = 8, F_NATIONAL = 9, F_MAKEUP = 10, F_MONTH = 11,
            F_TEMP = 22, F_TEMP2 = 23, F_COLD = 24, F_RAIN = 25, F_SNOW = 26, F_WIND = 27, F_TREND = 28, F_VACATION = 29, F_COUNT = 30;

    private double[] features(LocalDate d, Weather w) {
        double[] f = new double[F_COUNT];
        f[F_BIAS] = 1;
        Holiday h = holidays().get(d);
        boolean off = h != null && h.offDay();
        if (!off && d.getDayOfWeek() != DayOfWeek.MONDAY) {
            f[F_DOW + d.getDayOfWeek().getValue() - 2] = 1;
        }
        if (off) {
            f[F_OFF] = 1;
            f[F_SPRING] = h.name().contains("春节") ? 1 : 0;
            f[F_NATIONAL] = h.name().contains("国庆") ? 1 : 0;
        } else if (h != null) {
            f[F_MAKEUP] = 1;
        }
        if (d.getMonthValue() > 1) {
            f[F_MONTH + d.getMonthValue() - 2] = 1;
        }
        if (w != null && w.tempMean() != null) {
            double t = w.tempMean().doubleValue();
            f[F_TEMP] = t / 15;
            f[F_TEMP2] = t * t / 225;
            f[F_COLD] = Math.max(0, -22 - t) / 5;
            f[F_RAIN] = t > 0 && w.precipitation() != null ? Math.min(w.precipitation().doubleValue(), 20) / 10 : 0;
            f[F_SNOW] = w.snowfall() != null && w.snowfall().doubleValue() > 3 ? 1 : 0;
            f[F_WIND] = w.windMax() != null && w.windMax().doubleValue() > 30 ? 1 : 0;
        }
        f[F_TREND] = (d.toEpochDay() - LocalDate.of(2024, 1, 1).toEpochDay()) / 365.0;
        MonthDay md = MonthDay.from(d);
        f[F_VACATION] = !md.isBefore(MonthDay.of(1, 10)) && !md.isAfter(MonthDay.of(2, 25)) ? 1 : 0;
        return f;
    }

    /** 列出对预测值影响最大的可解释因素(对数空间贡献换算为百分比)。 */
    private List<String> drivers(double[] coef, double[] f, Weather w) {
        record D(String label, double effect) {
        }
        List<D> list = new ArrayList<>();
        double dow = 0;
        for (int i = F_DOW; i < F_OFF; i++) {
            dow += coef[i] * f[i];
        }
        list.add(new D("星期因素", dow));
        list.add(new D(f[F_SPRING] > 0 ? "春节假期" : f[F_NATIONAL] > 0 ? "国庆假期" : "法定节假日",
                coef[F_OFF] * f[F_OFF] + coef[F_SPRING] * f[F_SPRING] + coef[F_NATIONAL] * f[F_NATIONAL]));
        list.add(new D("调休上班日", coef[F_MAKEUP] * f[F_MAKEUP]));
        list.add(new D("极寒天气", coef[F_COLD] * f[F_COLD]));
        list.add(new D("降雨", coef[F_RAIN] * f[F_RAIN]));
        list.add(new D("降雪", coef[F_SNOW] * f[F_SNOW]));
        list.add(new D("大风", coef[F_WIND] * f[F_WIND]));
        list.add(new D("寒假", coef[F_VACATION] * f[F_VACATION]));
        List<String> out = new ArrayList<>(list.stream().filter(d -> Math.abs(d.effect()) >= 0.03)
                .sorted(Comparator.comparingDouble((D d) -> -Math.abs(d.effect()))).limit(3)
                .map(d -> "%s %+.0f%%".formatted(d.label(), (Math.exp(d.effect()) - 1) * 100)).toList());
        if (w != null && w.source().contains("同期均值")) {
            out.add("无天气预报, 采用历史同期均值");
        }
        return out;
    }

    // ---- 岭回归 ----
    private static Fit fit(double[][] x, double[] y, int rows) {
        int p = F_COUNT;
        double[][] a = new double[p][p + 1];
        for (int r = 0; r < rows; r++) {
            for (int i = 0; i < p; i++) {
                if (x[r][i] == 0) {
                    continue;
                }
                for (int j = 0; j < p; j++) {
                    a[i][j] += x[r][i] * x[r][j];
                }
                a[i][p] += x[r][i] * y[r];
            }
        }
        for (int i = 1; i < p; i++) {
            a[i][i] += RIDGE_LAMBDA;
        }
        a[0][0] += 1e-9;
        for (int col = 0; col < p; col++) {
            int pivot = col;
            for (int r = col + 1; r < p; r++) {
                if (Math.abs(a[r][col]) > Math.abs(a[pivot][col])) {
                    pivot = r;
                }
            }
            double[] tmp = a[col];
            a[col] = a[pivot];
            a[pivot] = tmp;
            for (int r = 0; r < p; r++) {
                if (r == col || a[r][col] == 0) {
                    continue;
                }
                double factor = a[r][col] / a[col][col];
                for (int c = col; c <= p; c++) {
                    a[r][c] -= factor * a[col][c];
                }
            }
        }
        double[] coef = new double[p];
        for (int i = 0; i < p; i++) {
            coef[i] = a[i][p] / a[i][i];
        }
        double sse = 0;
        for (int r = 0; r < rows; r++) {
            double e = y[r] - dot(coef, x[r]);
            sse += e * e;
        }
        return new Fit(coef, Math.sqrt(sse / Math.max(1, rows - p)));
    }

    private static double dot(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            s += a[i] * b[i];
        }
        return s;
    }

    // ---- 外部特征 ----
    private Map<LocalDate, Holiday> holidays() {
        Map<LocalDate, Holiday> h = holidays;
        if (h == null) {
            h = new HashMap<>();
            Map<LocalDate, Holiday> target = h;
            jdbc.sql("SELECT biz_date, name, is_off_day FROM holiday_calendar")
                    .query((rs, i) -> target.put(rs.getObject("biz_date", LocalDate.class), new Holiday(rs.getString("name"), rs.getBoolean("is_off_day"))))
                    .list();
            holidays = h;
        }
        return h;
    }

    public String holidayName(LocalDate d) {
        Holiday h = holidays().get(d);
        return h == null ? null : h.offDay() ? h.name() : h.name() + "调休上班";
    }

    public boolean isOffDay(LocalDate d) {
        Holiday h = holidays().get(d);
        if (h != null) {
            return h.offDay();
        }
        return d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    public Map<LocalDate, Weather> loadWeather(LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now(clock);
        Map<LocalDate, Weather> map = new HashMap<>();
        jdbc.sql("""
                SELECT biz_date, temp_max, temp_min, temp_mean, precipitation, snowfall, wind_max, weather_code, is_forecast, source
                FROM weather_daily WHERE location_code = 'harbin' AND biz_date >= ? AND biz_date <= ?
                """).params(from, to).query((rs, i) -> {
            LocalDate d = rs.getObject("biz_date", LocalDate.class);
            boolean future = d.isAfter(today);
            String source = rs.getString("source") + (future && !rs.getBoolean("is_forecast") ? "(回放模式: 以历史实况代替预报)" : "");
            return map.put(d, new Weather(rs.getBigDecimal("temp_max"), rs.getBigDecimal("temp_min"), rs.getBigDecimal("temp_mean"),
                    rs.getBigDecimal("precipitation"), rs.getBigDecimal("snowfall"), rs.getBigDecimal("wind_max"),
                    (Integer) rs.getObject("weather_code"), rs.getBoolean("is_forecast"), source));
        }).list();
        return map;
    }

    /** 没有该日气象时, 用历史上同期前后 7 天的平均值代替, 并在来源中注明。 */
    private Weather weatherFor(Map<LocalDate, Weather> weather, LocalDate d) {
        Weather w = weather.get(d);
        if (w != null) {
            return w;
        }
        return jdbc.sql("""
                SELECT round(avg(temp_max), 1) AS tmax, round(avg(temp_min), 1) AS tmin, round(avg(temp_mean), 1) AS tmean,
                       round(avg(precipitation), 1) AS p, round(avg(snowfall), 1) AS s, round(avg(wind_max), 1) AS wind
                FROM weather_daily WHERE location_code = 'harbin' AND NOT is_forecast
                  AND abs(extract(doy FROM biz_date) - ?) <= 7
                """).param(d.getDayOfYear()).query((rs, i) -> rs.getBigDecimal("tmean") == null ? null
                : new Weather(rs.getBigDecimal("tmax"), rs.getBigDecimal("tmin"), rs.getBigDecimal("tmean"), rs.getBigDecimal("p"),
                rs.getBigDecimal("s"), rs.getBigDecimal("wind"), null, true, "历史同期均值")).optional().orElse(null);
    }

    private List<String> sources(long siteId) {
        List<String> codes = new ArrayList<>(jdbc.sql("SELECT code FROM integration_source WHERE source_type = 'FLOW' AND site_id = ? AND enabled ORDER BY code")
                .param(siteId).query(String.class).list());
        codes.add("weather-open-meteo");
        codes.add("holiday-cn");
        return codes;
    }

    private static BigDecimal round(double v) {
        return BigDecimal.valueOf(Math.round(v));
    }

    private static BigDecimal dec(double v, int scale) {
        return BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP);
    }
}
