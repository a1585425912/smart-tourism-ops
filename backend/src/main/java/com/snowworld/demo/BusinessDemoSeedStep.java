package com.snowworld.demo;

import com.snowworld.forecast.ForecastService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.springframework.stereotype.Component;

/**
 * 多业态、旅行社团队与游客画像的演示数据, 并在最后训练预测模型。
 *
 * <p>业态单元使用通用编号名称(不使用真实商户名称), 仅借用 OSM 兴趣点的位置; 经营数据由景区客流按固定规则加扰动推算,
 * 用于展示"客流—业态"联动分析方法, 不代表任何真实商户的经营情况。
 */
@Component
public class BusinessDemoSeedStep implements DemoSeedStep {

    private static final int BUSINESS_DAYS = 120;
    private static final int GROUP_PAST_DAYS = 60;
    private static final int GROUP_FUTURE_DAYS = 5;

    private record Unit(long id, long siteId, String category, int capacity, double factor, double price) {
    }

    private final DemoFlowModel model;
    private final ForecastService forecast;

    public BusinessDemoSeedStep(DemoFlowModel model, ForecastService forecast) {
        this.model = model;
        this.forecast = forecast;
    }

    @Override
    public int order() {
        return 500;
    }

    @Override
    public String name() {
        return "业态/团队/画像(仿真)与预测模型";
    }

    @Override
    public boolean alreadySeeded(DemoSeedContext ctx) {
        return ctx.jdbc().sql("SELECT EXISTS (SELECT 1 FROM business_unit)").query(Boolean.class).single();
    }

    @Override
    public void seed(DemoSeedContext ctx) {
        Random rnd = new Random(7);
        long businessSource = ctx.ensureSource("business-demo", "酒店餐饮门店经营仿真数据", "BUSINESS", null, "系统内置仿真模型",
                "演示数据, 非真实经营数据", 86400);
        long groupSource = ctx.ensureSource("group-demo", "旅行社团队仿真数据", "GROUP", null, "系统内置仿真模型", "演示数据", 3600);
        long profileSource = ctx.ensureSource("profile-demo", "游客画像仿真统计", "PROFILE", null, "系统内置仿真模型", "演示数据", 86400);

        record Site(long id, String code) {
        }
        List<Site> sites = ctx.jdbc().sql("SELECT id, code FROM scenic_site ORDER BY id")
                .query((rs, i) -> new Site(rs.getLong(1), rs.getString(2))).list().stream().filter(s -> model.supports(s.code())).toList();
        LocalDate today = LocalDate.now(ctx.clock());

        // ---- 业态单元 ----
        List<Unit> units = new ArrayList<>();
        for (Site s : sites) {
            units.addAll(units(ctx, s.id(), s.code(), "HOTEL", "合作酒店", 6, rnd));
            units.addAll(units(ctx, s.id(), s.code(), "CATERING", "餐饮门店", 6, rnd));
            units.addAll(units(ctx, s.id(), s.code(), "RETAIL", "文创零售店", 4, rnd));
            if (!"ZYDJ".equals(s.code())) {
                units.add(unit(ctx, s.id(), s.code() + "-TICKET", "门票销售（演示）", "TICKET", null, null, 0, 0.92,
                        "BXDSJ".equals(s.code()) ? 260 : 60));
                units.add(unit(ctx, s.id(), s.code() + "-ENT", "园内娱乐项目（演示）", "ENTERTAINMENT", null, null, 0, 0.15, 80));
            }
        }

        // ---- 逐日经营数据 ----
        Map<Long, Map<LocalDate, Long>> entries = new HashMap<>();
        Map<LocalDate, Long> regional = new HashMap<>();
        long regionalMax = 1;
        for (Site s : sites) {
            Map<LocalDate, Long> m = new HashMap<>();
            for (LocalDate d = today.minusDays(BUSINESS_DAYS); d.isBefore(today); d = d.plusDays(1)) {
                long e = model.dailyEntries(s.code(), d);
                m.put(d, e);
                regionalMax = Math.max(regionalMax, regional.merge(d, e, Long::sum));
            }
            entries.put(s.id(), m);
        }
        for (Unit u : units) {
            for (LocalDate d = today.minusDays(BUSINESS_DAYS); d.isBefore(today); d = d.plusDays(1)) {
                long siteEntries = entries.get(u.siteId()).get(d);
                double noise = Math.exp(0.08 * rnd.nextGaussian());
                BigDecimal revenue;
                Integer orders;
                Integer guests = null;
                BigDecimal occupancy = null;
                if ("HOTEL".equals(u.category())) {
                    // 酒店入住率跟随三个景区合计客流
                    double demand = Math.pow(regional.get(d) / (double) regionalMax, 0.8);
                    double occ = Math.max(0.2, Math.min(0.98, 0.32 + 0.55 * demand * u.factor() + 0.04 * rnd.nextGaussian()));
                    occupancy = BigDecimal.valueOf(occ).setScale(4, RoundingMode.HALF_UP);
                    orders = (int) Math.round(u.capacity() * occ);
                    guests = (int) Math.round(orders * 1.8);
                    revenue = money(orders * u.price() * (1 + 0.4 * demand));
                } else {
                    if (siteEntries == 0) {
                        continue;
                    }
                    double base = siteEntries * u.factor() * noise;
                    if ("CATERING".equals(u.category())) {
                        guests = (int) Math.round(base);
                        orders = (int) Math.round(base / 2.4);
                        revenue = money(guests * u.price());
                    } else {
                        orders = (int) Math.round(base);
                        revenue = money(orders * u.price());
                    }
                }
                ctx.jdbc().sql("""
                        INSERT INTO business_daily (unit_id, biz_date, revenue, orders, guests, occupancy, source_id, data_mode)
                        VALUES (?, ?, ?, ?, ?, ?, ?, 'DEMO')
                        """).params(u.id(), d, revenue, orders, guests, occupancy, businessSource).update();
            }
        }

        // ---- 旅行社与团队 ----
        String[][] agencySpecs = {{"北国风光旅行社", "黑龙江"}, {"冰城假日旅行社", "黑龙江"}, {"松江国际旅行社", "黑龙江"},
                {"京华远途旅行社", "北京"}, {"岭南暖阳旅行社", "广东"}, {"珠江行知旅行社", "广东"}, {"申江悦途旅行社", "上海"},
                {"钱塘云游旅行社", "浙江"}, {"金陵同程旅行社", "江苏"}, {"齐鲁山海旅行社", "山东"}, {"巴蜀逍遥旅行社", "四川"},
                {"辽沈快线旅行社", "辽宁"}};
        List<long[]> agencies = new ArrayList<>();
        for (int i = 0; i < agencySpecs.length; i++) {
            long id = ctx.jdbc().sql("""
                    INSERT INTO travel_agency (code, name, license_no, region, contact_masked, data_mode)
                    VALUES (?, ?, ?, ?, ?, 'DEMO') RETURNING id
                    """).params("TA%03d".formatted(i + 1), agencySpecs[i][0] + "（演示）", "L-DEMO-%05d".formatted(10000 + i * 37),
                    agencySpecs[i][1], "13%d****%04d".formatted(rnd.nextInt(10), rnd.nextInt(10000))).query(Long.class).single();
            agencies.add(new long[]{id, i});
        }
        String surnames = "王李张刘陈杨赵黄周吴徐孙马朱胡郭何高林";
        OffsetDateTime now = OffsetDateTime.now(ctx.clock());
        int seq = 0;
        for (Site s : sites) {
            int perGroup = "ZYDJ".equals(s.code()) ? 5000 : 1300;
            for (LocalDate d = today.minusDays(GROUP_PAST_DAYS); !d.isAfter(today.plusDays(GROUP_FUTURE_DAYS)); d = d.plusDays(1)) {
                long e = model.dailyEntries(s.code(), d);
                int groups = (int) Math.min(40, e / perGroup);
                for (int g = 0; g < groups; g++) {
                    long[] agency = agencies.get(rnd.nextInt(agencies.size()));
                    int planned = 15 + rnd.nextInt(31);
                    String status;
                    Integer entered = 0;
                    OffsetDateTime enteredAt = null;
                    OffsetDateTime slot = d.atTime(9, 0).plusMinutes(g * 480L / Math.max(groups, 1)).atZone(DemoSeedContext.ZONE).toOffsetDateTime();
                    if (rnd.nextInt(50) == 0) {
                        status = "CANCELLED";
                    } else if (slot.isBefore(now)) {
                        status = slot.plusHours(4).isBefore(now) ? "LEFT" : "ENTERED";
                        entered = planned - rnd.nextInt(3);
                        enteredAt = slot;
                    } else {
                        status = "BOOKED";
                    }
                    ctx.jdbc().sql("""
                            INSERT INTO tour_group (group_code, agency_id, site_id, visit_date, planned_size, entered_count, guide_name,
                                guide_phone_masked, guide_cert_no, origin_region, status, entered_at, source_id, data_mode)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DEMO')
                            """).params("G%s-%05d".formatted(d.toString().replace("-", ""), ++seq), agency[0], s.id(), d, planned, entered,
                            surnames.charAt(rnd.nextInt(surnames.length())) + "导",
                            "1%d%d****%04d".formatted(3 + rnd.nextInt(6), rnd.nextInt(10), rnd.nextInt(10000)),
                            "D-2301-****%02d".formatted(rnd.nextInt(100)),
                            rnd.nextInt(5) == 0 ? null : agencySpecs[(int) agency[1]][1], status, enteredAt, groupSource).update();
                }
            }
        }

        // ---- 游客画像(月度聚合, 保留"未知"并记录覆盖率) ----
        Map<String, Object[][]> dims = Map.of(
                "ORIGIN", new Object[][]{{"黑龙江省内", .28}, {"广东", .11}, {"北京", .08}, {"上海", .07}, {"浙江", .07}, {"江苏", .06},
                        {"山东", .05}, {"四川", .04}, {"其他省份", .16}, {"境外", .02}, {"未知", .06}},
                "AGE", new Object[][]{{"18岁以下", .12}, {"18-25岁", .20}, {"26-35岁", .28}, {"36-45岁", .20}, {"46-60岁", .11},
                        {"60岁以上", .04}, {"未知", .05}},
                "GENDER", new Object[][]{{"女", .52}, {"男", .42}, {"未知", .06}},
                "CHANNEL", new Object[][]{{"线上平台预订", .55}, {"旅行社团队", .20}, {"现场购票", .15}, {"年卡及其他", .05}, {"未知", .05}},
                "STAY", new Object[][]{{"2小时以内", .15}, {"2-4小时", .45}, {"4-6小时", .25}, {"6小时以上", .08}, {"未知", .07}});
        for (Site s : sites) {
            for (int back = 0; back < 6; back++) {
                LocalDate month = today.withDayOfMonth(1).minusMonths(back);
                long monthEntries = 0;
                for (LocalDate d = month; d.isBefore(month.plusMonths(1)) && d.isBefore(today); d = d.plusDays(1)) {
                    monthEntries += model.dailyEntries(s.code(), d);
                }
                if (monthEntries == 0) {
                    continue;
                }
                double coverage = 0.55 + 0.03 * ((s.id() + back) % 5);
                int sample = (int) (monthEntries * coverage);
                for (var dim : dims.entrySet()) {
                    for (Object[] bucket : dim.getValue()) {
                        double share = (double) bucket[1] * (1 + 0.08 * rnd.nextGaussian());
                        ctx.jdbc().sql("""
                                INSERT INTO visitor_profile_stat (site_id, stat_month, dimension, bucket, visitors, sample_size, coverage, source_id, data_mode)
                                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'DEMO')
                                """).params(s.id(), month, dim.getKey(), bucket[0], (int) Math.max(1, sample * share), sample,
                                BigDecimal.valueOf(coverage).setScale(4, RoundingMode.HALF_UP), profileSource).update();
                    }
                }
            }
        }

        // ---- 预测模型: 训练、回测并保存首个预测批次 ----
        sites.forEach(s -> forecast.refresh(s.id()));
    }

    private List<Unit> units(DemoSeedContext ctx, long siteId, String siteCode, String category, String label, int max, Random rnd) {
        List<double[]> points = ctx.jdbc().sql("SELECT ST_X(geom), ST_Y(geom) FROM poi WHERE site_id = ? AND category = ? ORDER BY id LIMIT ?")
                .params(siteId, category, max).query((rs, i) -> new double[]{rs.getDouble(1), rs.getDouble(2)}).list();
        List<Unit> out = new ArrayList<>();
        // 周边没有足够的 OSM 兴趣点时仍保证每类至少 3 个单元(位置留空)
        for (int i = 0; i < Math.max(3, points.size()); i++) {
            double[] p = i < points.size() ? points.get(i) : null;
            int capacity = "HOTEL".equals(category) ? 80 + rnd.nextInt(220) : 0;
            double factor = switch (category) {
                case "HOTEL" -> 0.85 + 0.3 * rnd.nextDouble();
                case "CATERING" -> 0.004 + 0.008 * rnd.nextDouble();
                default -> 0.002 + 0.004 * rnd.nextDouble();
            };
            double price = switch (category) {
                case "HOTEL" -> 280 + rnd.nextInt(400);
                case "CATERING" -> 45 + rnd.nextInt(65);
                default -> 60 + rnd.nextInt(90);
            };
            out.add(unit(ctx, siteId, "%s-%s-%02d".formatted(siteCode, category.substring(0, 3), i + 1),
                    "%s %02d（演示）".formatted(label, i + 1), category, p == null ? null : p[0], p == null ? null : p[1], capacity, factor, price));
        }
        return out;
    }

    private Unit unit(DemoSeedContext ctx, long siteId, String code, String name, String category, Double lon, Double lat,
                      int capacity, double factor, double price) {
        long id = ctx.jdbc().sql("""
                INSERT INTO business_unit (site_id, code, name, category, location, capacity, data_mode)
                VALUES (?, ?, ?, ?, CASE WHEN ?::float8 IS NULL THEN NULL ELSE ST_SetSRID(ST_Point(?::float8, ?::float8), 4326) END, ?, 'DEMO') RETURNING id
                """).params(siteId, code, name, category, lon, lon, lat, capacity == 0 ? null : capacity).query(Long.class).single();
        return new Unit(id, siteId, category, capacity, factor, price);
    }

    private static BigDecimal money(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }
}
