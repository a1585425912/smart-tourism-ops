package com.snowworld.demo;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.MonthDay;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 演示客流的确定性仿真模型: 同一景区、同一时刻永远得到同一数值, 因此历史回灌与实时模拟可以重复执行而不产生差异。
 *
 * <p>日入园量 = 基数 × 年增长 × 星期 × 节假日 × 季节 × 天气 × 随机扰动, 其中天气与节假日取自真实开放数据
 * (Open-Meteo 历史气象、国务院节假日安排), 客流数值本身是仿真结果, 一律标记为 DEMO。
 * 基数、承载量、开放时间均为演示配置, 不代表景区官方数据。
 */
@Component
public class DemoFlowModel {

    public static final int SLOT_MINUTES = 10;
    public static final int SLOTS = 24 * 60 / SLOT_MINUTES;

    /** peaks: {峰值时刻(分钟), 标准差(分钟), 权重} */
    record Profile(double base, LocalTime open, LocalTime close, int stayMinutes, double[][] peaks,
                   double[] monthFactor, MonthDay seasonStart, MonthDay seasonEnd,
                   double springFestival, double nationalDay, double otherHoliday) {
    }

    public record DayCurve(long[] entries, long[] exits, long[] inPark, int firstSlot, int lastSlot) {
    }

    private record Weather(double tempMean, double precipitation, double snowfall, double wind) {
    }

    private record Holiday(String name, boolean offDay) {
    }

    private static final double[] FLAT = {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1};

    static final Map<String, Profile> PROFILES = Map.of(
            "BXDSJ", new Profile(20000, LocalTime.of(11, 0), LocalTime.of(21, 30), 200,
                    new double[][]{{16 * 60, 120, 1.0}, {13 * 60, 90, 0.5}}, FLAT,
                    MonthDay.of(12, 20), MonthDay.of(2, 28), 2.0, 1.0, 1.5),
            "TYD", new Profile(7000, LocalTime.of(8, 30), LocalTime.of(17, 0), 150,
                    new double[][]{{10 * 60 + 30, 80, 1.0}, {13 * 60 + 30, 70, 0.7}},
                    new double[]{1.5, 1.4, 0.6, 0.7, 1.0, 1.3, 1.6, 1.5, 1.0, 0.9, 0.5, 1.1},
                    null, null, 1.7, 2.0, 1.5),
            "ZYDJ", new Profile(45000, LocalTime.of(8, 0), LocalTime.of(23, 0), 75,
                    new double[][]{{14 * 60, 120, 0.8}, {19 * 60 + 30, 90, 1.0}},
                    new double[]{1.5, 1.4, 0.8, 0.8, 1.0, 1.2, 1.4, 1.4, 1.0, 1.0, 0.7, 1.2},
                    null, null, 1.8, 1.9, 1.45));

    private final JdbcClient jdbc;
    private volatile Map<LocalDate, Weather> weather;
    private volatile Map<LocalDate, Holiday> holidays;
    private final Map<String, DayCurve> curveCache = new ConcurrentHashMap<>();

    public DemoFlowModel(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean supports(String siteCode) {
        return PROFILES.containsKey(siteCode);
    }

    /** 天气/节假日数据更新后调用。 */
    public void reload() {
        weather = null;
        holidays = null;
        curveCache.clear();
    }

    private void ensureLoaded() {
        if (weather != null && holidays != null) {
            return;
        }
        Map<LocalDate, Weather> w = new ConcurrentHashMap<>();
        jdbc.sql("SELECT biz_date, temp_mean, precipitation, snowfall, wind_max FROM weather_daily WHERE location_code = 'harbin'")
                .query((rs, i) -> w.put(rs.getObject("biz_date", LocalDate.class),
                        new Weather(rs.getDouble("temp_mean"), rs.getDouble("precipitation"), rs.getDouble("snowfall"), rs.getDouble("wind_max"))))
                .list();
        Map<LocalDate, Holiday> h = new ConcurrentHashMap<>();
        jdbc.sql("SELECT biz_date, name, is_off_day FROM holiday_calendar")
                .query((rs, i) -> h.put(rs.getObject("biz_date", LocalDate.class),
                        new Holiday(rs.getString("name"), rs.getBoolean("is_off_day"))))
                .list();
        weather = w;
        holidays = h;
    }

    public boolean inSeason(String siteCode, LocalDate date) {
        Profile p = PROFILES.get(siteCode);
        if (p.seasonStart() == null) {
            return true;
        }
        MonthDay md = MonthDay.from(date);
        // 跨年季节: 12-20 至次年 02-28
        return !md.isBefore(p.seasonStart()) || !md.isAfter(p.seasonEnd());
    }

    public long dailyEntries(String siteCode, LocalDate date) {
        ensureLoaded();
        Profile p = PROFILES.get(siteCode);
        if (!inSeason(siteCode, date)) {
            return 0;
        }
        double f = p.base() * (1 + 0.06 * (date.getYear() - 2024 + (date.getDayOfYear() - 1) / 365.0));
        Holiday holiday = holidays.get(date);
        DayOfWeek dow = date.getDayOfWeek();
        if (holiday != null && holiday.offDay()) {
            f *= holiday.name().contains("春节") ? p.springFestival()
                    : holiday.name().contains("国庆") ? p.nationalDay() : p.otherHoliday();
        } else if (holiday != null) {
            f *= 0.9;
        } else if (dow == DayOfWeek.SATURDAY) {
            f *= 1.30;
        } else if (dow == DayOfWeek.SUNDAY) {
            f *= 1.22;
        } else if (dow == DayOfWeek.FRIDAY) {
            f *= 1.08;
        }
        f *= p.monthFactor()[date.getMonthValue() - 1];
        if (p.seasonStart() != null) {
            f *= seasonRamp(p, date);
            MonthDay md = MonthDay.from(date);
            if (!md.isBefore(MonthDay.of(1, 10)) && !md.isAfter(MonthDay.of(2, 25))) {
                f *= 1.15;
            }
        }
        Weather w = weather.get(date);
        if (w != null) {
            if (w.tempMean() < -22) {
                f *= 1 - Math.min(0.25, (-22 - w.tempMean()) * 0.02);
            }
            if (w.tempMean() > 0) {
                f *= 1 - Math.min(0.4, w.precipitation() * 0.02);
            }
            if (w.snowfall() > 3) {
                f *= 0.93;
            }
            if (w.wind() > 30) {
                f *= 0.93;
            }
        }
        f *= Math.exp(0.07 * gauss(siteCode, date, -1));
        return Math.round(f);
    }

    /** 开园初期与闭园前客流爬坡/回落。 */
    private static double seasonRamp(Profile p, LocalDate date) {
        LocalDate start = p.seasonStart().atYear(date.getMonthValue() >= 7 ? date.getYear() : date.getYear() - 1);
        LocalDate end = p.seasonEnd().atYear(start.getYear() + 1);
        long sinceStart = date.toEpochDay() - start.toEpochDay();
        long toEnd = end.toEpochDay() - date.toEpochDay();
        double ramp = 1;
        if (sinceStart < 5) {
            ramp *= 0.6 + 0.08 * sinceStart;
        }
        if (toEnd < 7) {
            ramp *= 0.6 + 0.057 * toEnd;
        }
        return ramp;
    }

    /** 某日每 10 分钟的入园、出园人次与在园存量。闭园日返回 null。 */
    public DayCurve curve(String siteCode, LocalDate date) {
        long daily = dailyEntries(siteCode, date);
        if (daily <= 0) {
            return null;
        }
        if (curveCache.size() > 600) {
            curveCache.clear();
        }
        return curveCache.computeIfAbsent(siteCode + date, k -> buildCurve(siteCode, date, daily));
    }

    private DayCurve buildCurve(String siteCode, LocalDate date, long daily) {
        Profile p = PROFILES.get(siteCode);
        int openSlot = p.open().toSecondOfDay() / 60 / SLOT_MINUTES;
        int closeSlot = (p.close().toSecondOfDay() / 60 + SLOT_MINUTES - 1) / SLOT_MINUTES;
        int lastEntrySlot = closeSlot - 6;
        int lastSlot = Math.min(SLOTS - 1, closeSlot + 3);
        double[] weight = new double[SLOTS];
        double total = 0;
        for (int s = openSlot; s <= lastEntrySlot; s++) {
            double t = s * SLOT_MINUTES + SLOT_MINUTES / 2.0;
            for (double[] peak : p.peaks()) {
                double z = (t - peak[0]) / peak[1];
                weight[s] += peak[2] * Math.exp(-0.5 * z * z);
            }
            weight[s] += 0.03;
            total += weight[s];
        }
        long[] entries = new long[SLOTS];
        for (int s = openSlot; s <= lastEntrySlot; s++) {
            entries[s] = Math.round(daily * weight[s] / total * Math.exp(0.10 * gauss(siteCode, date, s)));
        }
        int stay = p.stayMinutes() / SLOT_MINUTES;
        long[] exits = new long[SLOTS];
        long[] inPark = new long[SLOTS];
        long in = 0;
        for (int s = openSlot; s <= lastSlot; s++) {
            in += entries[s];
            double planned = 0.5 * at(entries, s - stay) + 0.25 * at(entries, s - stay - 3) + 0.25 * at(entries, s - stay + 3);
            long out = s >= lastSlot ? in : Math.min(in, Math.round(planned));
            exits[s] = out;
            in -= out;
            inPark[s] = in;
        }
        return new DayCurve(entries, exits, inPark, openSlot, lastSlot);
    }

    private static long at(long[] a, int i) {
        return i >= 0 && i < a.length ? a[i] : 0;
    }

    /**
     * 各区域瞬时人数: 在园人数的约 80% 分布在各区域(其余在通行途中), 按区域容量、固定热度系数与随时段变化的偏好分配。
     *
     * @param capacities 各区域容量, 顺序固定
     */
    public long[] zoneCounts(String siteCode, LocalDate date, int slot, long inPark, int[] capacities) {
        int n = capacities.length;
        double[] w = new double[n];
        double sum = 0;
        double hour = slot * SLOT_MINUTES / 60.0;
        for (int i = 0; i < n; i++) {
            double hot = 1 + 0.5 * ((i * 37) % 5) / 4.0;
            double mod = 1 + 0.35 * Math.sin(2 * Math.PI * hour / 8 + i * 1.3);
            w[i] = capacities[i] * hot * mod;
            sum += w[i];
        }
        long[] out = new long[n];
        for (int i = 0; i < n; i++) {
            double v = sum == 0 ? 0 : inPark * 0.8 * w[i] / sum * (1 + 0.06 * gauss(siteCode + "#z" + i, date, slot));
            out[i] = Math.max(0, Math.round(v));
        }
        return out;
    }

    /** 排队人数: 与所在区域人数成比例。 */
    public long queueLength(String siteCode, LocalDate date, int slot, long zoneCount, int pointOrdinal) {
        double share = 0.035 + 0.012 * (pointOrdinal % 4);
        double v = zoneCount * share * (1 + 0.15 * gauss(siteCode + "#q" + pointOrdinal, date, slot));
        return Math.max(0, Math.round(v));
    }

    /** 实测等待时间相对"排队人数/服务能力"估计值的偏差系数。 */
    public double measuredFactor(String siteCode, LocalDate date, int slot, int pointOrdinal) {
        return 1 + 0.12 * gauss(siteCode + "#m" + pointOrdinal, date, slot);
    }

    static double gauss(String key, LocalDate date, int slot) {
        long h = key.hashCode() * 0x9E3779B97F4A7C15L + date.toEpochDay() * 0xC2B2AE3D27D4EB4FL + slot * 0x165667B19E3779F9L;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        double g = new Random(h).nextGaussian();
        return Math.max(-2.5, Math.min(2.5, g));
    }
}
