package com.snowworld.demo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 基础演示数据: 景区(边界取自 OpenStreetMap)、区域围栏、设备、排队点、数据源、账号, 以及真实的气象与节假日开放数据。
 * 景区容量、区域划分、设备点位均为演示配置, 不代表景区官方数据。
 */
@Component
public class BaseDemoSeedStep implements DemoSeedStep {

    private static final Logger log = LoggerFactory.getLogger(BaseDemoSeedStep.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** fx/fy: 区域中心在景区外接矩形内的相对位置; 实际多边形为圆形缓冲区与景区边界的交集。 */
    private record ZoneSpec(String code, String name, String type, double fx, double fy, int capacity) {
    }

    private record QueueSpec(String zoneCode, String name, String type, double ratePerMin) {
    }

    private final PasswordEncoder encoder;
    private final DemoFlowModel model;

    public BaseDemoSeedStep(PasswordEncoder encoder, DemoFlowModel model) {
        this.encoder = encoder;
        this.model = model;
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public String name() {
        return "基础数据(景区/区域/设备/账号/开放数据)";
    }

    @Override
    public boolean alreadySeeded(DemoSeedContext ctx) {
        return ctx.jdbc().sql("SELECT count(*) FROM scenic_site").query(Long.class).single() > 0;
    }

    @Override
    public void seed(DemoSeedContext ctx) throws IOException {
        Map<Long, JsonNode> osm = new HashMap<>();
        for (String layer : List.of("areas", "roads")) {
            JsonNode root = JSON.readTree(Files.readString(ctx.dataDir().resolve("osm/" + layer + ".geojson")));
            for (JsonNode f : root.get("features")) {
                osm.put(f.get("properties").get("osm_id").asLong(), f.get("geometry"));
            }
        }
        long bxdsj = polygonSite(ctx, osm, 350536247L, "BXDSJ", "哈尔滨冰雪大世界", "冰雪大世界", 40000, "11:00", "21:30",
                "SEASONAL", 1, "冰雪季开放(演示配置为每年 12 月 20 日至次年 2 月 28 日)",
                "以冰雕、雪雕、冰滑梯和夜间灯光为核心的大型冰雪主题乐园。");
        long tyd = polygonSite(ctx, osm, 350484254L, "TYD", "太阳岛风景区", "太阳岛", 30000, "08:30", "17:00",
                "OPEN", 2, "全年开放, 冬季举办雪雕艺术展", "松花江北岸的城市生态风景区, 冬季以大型雪雕展著称。");
        long zydj = streetSite(ctx, osm, List.of(300842743L, 1511498728L));

        zones(ctx, bxdsj, 110, List.of(
                new ZoneSpec("E1", "主入口广场", "ENTRANCE", 0.60, 0.20, 5000),
                new ZoneSpec("A1", "冰雕主景区", "ATTRACTION", 0.55, 0.55, 7000),
                new ZoneSpec("A2", "冰滑梯体验区", "ATTRACTION", 0.25, 0.75, 4000),
                new ZoneSpec("A3", "雪花摩天轮区", "ATTRACTION", 0.20, 0.30, 3500),
                new ZoneSpec("S1", "餐饮暖房服务区", "SERVICE", 0.40, 0.42, 2500),
                new ZoneSpec("A4", "冰雪演艺广场", "ATTRACTION", 0.72, 0.42, 4000)));
        zones(ctx, tyd, 140, List.of(
                new ZoneSpec("E1", "景区正门广场", "ENTRANCE", 0.45, 0.20, 4000),
                new ZoneSpec("A1", "雪雕艺术展区", "ATTRACTION", 0.60, 0.50, 6000),
                new ZoneSpec("A2", "太阳湖景区", "ATTRACTION", 0.30, 0.75, 4500),
                new ZoneSpec("A3", "花卉园区", "ATTRACTION", 0.15, 0.45, 3000),
                new ZoneSpec("S1", "游船码头服务区", "SERVICE", 0.75, 0.75, 2500)));
        streetZones(ctx, zydj, osm);

        devices(ctx);
        queues(ctx, bxdsj, List.of(new QueueSpec("A2", "冰滑梯排队区", "RIDE", 6), new QueueSpec("A3", "雪花摩天轮", "RIDE", 4),
                new QueueSpec("E1", "入口检票口", "GATE", 30), new QueueSpec("S1", "暖房餐饮取餐口", "SERVICE", 5)));
        queues(ctx, tyd, List.of(new QueueSpec("A1", "雪雕展馆入口", "SHOW", 12), new QueueSpec("S1", "游船码头", "RIDE", 3),
                new QueueSpec("E1", "正门检票口", "GATE", 25)));
        queues(ctx, zydj, List.of(new QueueSpec("C2", "中段热门打卡点", "SERVICE", 2.5), new QueueSpec("C3", "北段观景平台", "SHOW", 8)));

        sources(ctx);
        users(ctx, bxdsj, tyd, zydj);
        pois(ctx);
        weather(ctx);
        holidays(ctx);
        model.reload();
    }

    private long polygonSite(DemoSeedContext ctx, Map<Long, JsonNode> osm, long osmId, String code, String name, String shortName,
                             int capacity, String open, String close, String status, int sort, String season, String desc) {
        JsonNode geometry = osm.get(osmId);
        if (geometry == null) {
            throw new IllegalStateException("data/osm 中缺少景区边界 way/" + osmId + ", 请先运行 scripts/data/fetch_osm.py");
        }
        return ctx.jdbc().sql("""
                WITH g AS (SELECT ST_CollectionExtract(ST_MakeValid(ST_SetSRID(ST_GeomFromGeoJSON(?), 4326)), 3) AS geom)
                INSERT INTO scenic_site (code, name, short_name, capacity, warn_ratio, open_time, close_time, season_note,
                    description, center, boundary, boundary_src, status, sort_order)
                SELECT ?, ?, ?, ?, 0.8, ?::time, ?::time, ?, ?, ST_PointOnSurface(geom), ST_Multi(geom), ?, ?, ? FROM g
                RETURNING id
                """).params(JSON.writeValueAsString(geometry), code, name, shortName, capacity, open, close, season,
                        desc + "（容量与开放时间为演示配置）", "OpenStreetMap way/" + osmId + " (ODbL)", status, sort)
                .query(Long.class).single();
    }

    /** 中央大街是开放式步行街, 以 OSM 街道中心线两侧各 30 米缓冲区作为管控范围。 */
    private long streetSite(DemoSeedContext ctx, Map<Long, JsonNode> osm, List<Long> wayIds) {
        List<String> lines = new ArrayList<>();
        for (Long id : wayIds) {
            if (osm.get(id) == null) {
                throw new IllegalStateException("data/osm 中缺少中央大街中心线 way/" + id);
            }
            lines.add(JSON.writeValueAsString(osm.get(id)));
        }
        return ctx.jdbc().sql("""
                WITH g AS (SELECT ST_Union(ST_Buffer(ST_SetSRID(ST_GeomFromGeoJSON(j), 4326)::geography, 30)::geometry) AS geom
                           FROM unnest(ARRAY[?::text, ?::text]) AS j)
                INSERT INTO scenic_site (code, name, short_name, capacity, warn_ratio, open_time, close_time, season_note,
                    description, center, boundary, boundary_src, status, sort_order)
                SELECT 'ZYDJ', '中央大街步行街', '中央大街', 35000, 0.8, '08:00'::time, '23:00'::time, '全年开放的开放式街区',
                       '集建筑艺术、餐饮、购物于一体的历史文化步行街区。（容量与统计时段为演示配置）',
                       ST_PointOnSurface(geom), ST_Multi(ST_CollectionExtract(geom, 3)),
                       'OpenStreetMap way/300842743, way/1511498728 中心线 30 米缓冲区 (ODbL)', 'OPEN', 3 FROM g
                RETURNING id
                """).params(lines.get(0), lines.get(1)).query(Long.class).single();
    }

    private void zones(DemoSeedContext ctx, long siteId, int radiusMeters, List<ZoneSpec> specs) {
        double[] box = ctx.jdbc().sql("SELECT ST_XMin(boundary), ST_YMin(boundary), ST_XMax(boundary), ST_YMax(boundary) FROM scenic_site WHERE id = ?")
                .param(siteId).query((rs, i) -> new double[]{rs.getDouble(1), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4)}).single();
        for (ZoneSpec z : specs) {
            double lon = box[0] + z.fx() * (box[2] - box[0]);
            double lat = box[1] + z.fy() * (box[3] - box[1]);
            int n = ctx.jdbc().sql("""
                    INSERT INTO scenic_zone (site_id, code, name, zone_type, boundary, capacity, warn_ratio, count_source, remark)
                    SELECT ?, ?, ?, ?, poly, ?, 0.8, ?, '演示划分, 非景区官方分区'
                    FROM (SELECT (ST_Dump(ST_Intersection(s.boundary,
                              ST_Buffer(ST_SetSRID(ST_Point(?, ?), 4326)::geography, ?, 'quad_segs=3')::geometry))).geom AS poly
                          FROM scenic_site s WHERE s.id = ?) t
                    WHERE GeometryType(poly) = 'POLYGON' AND ST_Area(poly::geography) > 2000
                    ORDER BY ST_Area(poly) DESC LIMIT 1
                    """).params(siteId, z.code(), z.name(), z.type(), z.capacity(), "区域客流计数器（演示）", lon, lat, radiusMeters, siteId)
                    .update();
            if (n == 0) {
                log.warn("区域 {} 与景区边界无有效交集, 已跳过", z.name());
            }
        }
    }

    private void streetZones(DemoSeedContext ctx, long siteId, Map<Long, JsonNode> osm) {
        String main = JSON.writeValueAsString(osm.get(300842743L));
        String south = JSON.writeValueAsString(osm.get(1511498728L));
        Object[][] segments = {
                {"C0", "南端入口段", south, 0.0, 1.0, 4000, "ENTRANCE"},
                {"C1", "南段商业街区", main, 0.0, 0.33, 8000, "COMMERCIAL"},
                {"C2", "中段商业街区", main, 0.33, 0.67, 9000, "COMMERCIAL"},
                {"C3", "北段江畔街区", main, 0.67, 1.0, 9000, "COMMERCIAL"}};
        for (Object[] s : segments) {
            ctx.jdbc().sql("""
                    INSERT INTO scenic_zone (site_id, code, name, zone_type, boundary, capacity, warn_ratio, count_source, remark)
                    SELECT ?, ?, ?, ?, (ST_Dump(ST_Buffer(ST_LineSubstring(ST_SetSRID(ST_GeomFromGeoJSON(?), 4326), ?, ?)::geography,
                               26, 'endcap=flat')::geometry)).geom, ?, 0.8, '街区断面客流计数器（演示）', '演示划分, 非官方分区'
                    LIMIT 1
                    """).params(siteId, s[0], s[1], s[6], s[2], s[3], s[4], s[5]).update();
        }
    }

    /** 每个区域: 1 台客流计数器 + 1~2 路摄像头(首路为重点点位); 入口区域另有 2 台闸机。少量设备置为离线/故障以展示异常状态。 */
    private void devices(DemoSeedContext ctx) {
        OffsetDateTime now = OffsetDateTime.now(ctx.clock());
        record Z(long id, long siteId, String siteCode, String code, String name, String type) {
        }
        List<Z> zones = ctx.jdbc().sql("""
                SELECT z.id, z.site_id, s.code AS site_code, z.code, z.name, z.zone_type FROM scenic_zone z
                JOIN scenic_site s ON s.id = z.site_id ORDER BY z.id
                """).query((rs, i) -> new Z(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6))).list();
        int cam = 0;
        for (Z z : zones) {
            String prefix = z.siteCode() + "-" + z.code();
            device(ctx, z.id(), z.siteId(), prefix + "-CNT", z.name() + "客流计数器", "COUNTER", false, 1, "ONLINE", now, null);
            int cameras = "SERVICE".equals(z.type()) ? 1 : 2;
            for (int c = 1; c <= cameras; c++) {
                cam++;
                String status = cam % 11 == 0 ? "OFFLINE" : cam % 17 == 0 ? "FAULT" : "ONLINE";
                device(ctx, z.id(), z.siteId(), prefix + "-CAM" + c, z.name() + (c == 1 ? "全景" : "通道") + "摄像头", "CAMERA",
                        c == 1, 1 + c, status, "ONLINE".equals(status) ? now : now.minusHours(3), "demo-cam-" + cam);
            }
            if ("ENTRANCE".equals(z.type())) {
                for (int g = 1; g <= 2; g++) {
                    device(ctx, z.id(), z.siteId(), prefix + "-GATE" + g, z.name() + g + "号闸机", "GATE", false, 3 + g, "ONLINE", now, null);
                }
            }
        }
    }

    private void device(DemoSeedContext ctx, long zoneId, long siteId, String code, String name, String type, boolean keyPoint,
                        int pointIndex, String status, OffsetDateTime statusAt, String streamRef) {
        ctx.jdbc().sql("""
                INSERT INTO device (site_id, zone_id, code, name, device_type, key_point, location, vendor, protocol,
                    source_device_id, stream_ref, status, status_at, data_mode)
                SELECT ?, z.id, ?, ?, ?, ?, ST_GeometryN(ST_GeneratePoints(z.boundary, 6, 7), ?), '演示适配器', 'DEMO', ?, ?, ?, ?, 'DEMO'
                FROM scenic_zone z WHERE z.id = ?
                """).params(siteId, code, name, type, keyPoint, pointIndex, code, streamRef, status, statusAt, zoneId).update();
    }

    private void queues(DemoSeedContext ctx, long siteId, List<QueueSpec> specs) {
        OffsetDateTime now = OffsetDateTime.now(ctx.clock());
        String siteCode = ctx.jdbc().sql("SELECT code FROM scenic_site WHERE id = ?").param(siteId).query(String.class).single();
        int n = 0;
        for (QueueSpec q : specs) {
            Long zoneId = ctx.jdbc().sql("SELECT id FROM scenic_zone WHERE site_id = ? AND code = ?").params(siteId, q.zoneCode())
                    .query(Long.class).optional().orElse(null);
            if (zoneId == null) {
                continue;
            }
            String code = siteCode + "-Q" + (++n);
            device(ctx, zoneId, siteId, code, q.name() + "排队检测器", "COUNTER", false, 6, "ONLINE", now, null);
            ctx.jdbc().sql("""
                    INSERT INTO queue_point (site_id, zone_id, device_id, name, point_type, location, service_rate_per_min)
                    SELECT d.site_id, d.zone_id, d.id, ?, ?, d.location, ? FROM device d WHERE d.code = ?
                    """).params(q.name(), q.type(), q.ratePerMin(), code).update();
        }
    }

    private void sources(DemoSeedContext ctx) {
        ctx.jdbc().sql("SELECT id, code, short_name FROM scenic_site ORDER BY id")
                .query((rs, i) -> new Object[]{rs.getLong(1), rs.getString(2), rs.getString(3)}).list()
                .forEach(s -> ctx.ensureSource("flow-demo-" + ((String) s[1]).toLowerCase(), s[2] + "客流与排队仿真数据", "FLOW",
                        (Long) s[0], "系统内置仿真模型", "演示数据, 非真实客流", 600));
        ctx.ensureSource("video-demo", "视频演示适配器", "VIDEO", null, "系统内置", "未接入真实视频平台", null);
        realSource(ctx, "weather-open-meteo", "哈尔滨历史气象", "WEATHER", "Open-Meteo Historical Weather API", "CC BY 4.0");
        realSource(ctx, "holiday-cn", "法定节假日安排", "HOLIDAY", "国务院办公厅节假日通知(holiday-cn 整理)", "MIT");
        realSource(ctx, "map-osm", "地图要素与兴趣点", "MAP", "OpenStreetMap contributors", "ODbL 1.0");
    }

    private void realSource(DemoSeedContext ctx, String code, String name, String type, String provider, String license) {
        ctx.jdbc().sql("""
                INSERT INTO integration_source (code, name, source_type, data_mode, provider, license_note, health, last_success_at)
                VALUES (?, ?, ?, 'REAL', ?, ?, 'OK', now()) ON CONFLICT (code) DO NOTHING
                """).params(code, name, type, provider, license).update();
    }

    private void users(DemoSeedContext ctx, long bxdsj, long tyd, long zydj) {
        String password = ctx.props().demo().userPassword();
        if (password == null || password.isBlank()) {
            password = UUID.randomUUID().toString();
            log.warn("未配置 snow.demo.user-password, 已为演示账号生成随机密码: {}", password);
        }
        String hash = encoder.encode(password);
        user(ctx, hash, "admin", "集团管理员", "GROUP_ADMIN", true);
        user(ctx, hash, "bxdsj_admin", "冰雪大世界管理员", "SITE_ADMIN", false, bxdsj);
        user(ctx, hash, "tyd_admin", "太阳岛管理员", "SITE_ADMIN", false, tyd);
        user(ctx, hash, "operator", "运营专员", "OPERATOR", true);
        user(ctx, hash, "inspector1", "巡检员甲", "INSPECTOR", false, bxdsj, zydj);
        user(ctx, hash, "inspector2", "巡检员乙", "INSPECTOR", false, tyd, bxdsj);
        user(ctx, hash, "reviewer", "复核员", "REVIEWER", false, bxdsj, tyd, zydj);
        user(ctx, hash, "viewer", "只读查看者", "VIEWER", false, bxdsj);
    }

    private void user(DemoSeedContext ctx, String hash, String username, String displayName, String role, boolean allSites, long... sites) {
        long id = ctx.jdbc().sql("INSERT INTO app_user (username, password_hash, display_name, all_sites) VALUES (?, ?, ?, ?) RETURNING id")
                .params(username, hash, displayName, allSites).query(Long.class).single();
        ctx.jdbc().sql("INSERT INTO user_role (user_id, role_code) VALUES (?, ?)").params(id, role).update();
        for (long site : sites) {
            ctx.jdbc().sql("INSERT INTO user_scenic_scope (user_id, site_id) VALUES (?, ?)").params(id, site).update();
        }
    }

    private void pois(DemoSeedContext ctx) throws IOException {
        Path file = ctx.dataDir().resolve("osm/pois.geojson");
        if (!Files.isRegularFile(file)) {
            log.warn("缺少 {}, 跳过兴趣点导入", file);
            return;
        }
        int count = 0;
        for (JsonNode f : JSON.readTree(Files.readString(file)).get("features")) {
            JsonNode p = f.get("properties");
            String sub = text(p, "tourism", "amenity", "shop", "public_transport");
            String category = category(p);
            double[] c = centroid(f.get("geometry"));
            if (c == null || category == null) {
                continue;
            }
            String name = text(p, "name", "name:zh", "name:en");
            count += ctx.jdbc().sql("""
                    INSERT INTO poi (site_id, osm_ref, name, category, sub_type, geom)
                    SELECT (SELECT id FROM scenic_site WHERE ST_DWithin(boundary::geography, pt::geography, 1500)
                            ORDER BY ST_Distance(boundary::geography, pt::geography) LIMIT 1), ?, ?, ?, ?, pt
                    FROM (SELECT ST_SetSRID(ST_Point(?, ?), 4326) AS pt) t
                    ON CONFLICT (osm_ref) DO NOTHING
                    """).params(p.get("osm_type").asString() + "/" + p.get("osm_id").asLong(),
                            name == null ? null : name.substring(0, Math.min(name.length(), 200)), category, sub, c[0], c[1]).update();
        }
        log.info("导入 OSM 兴趣点 {} 个", count);
    }

    private static String category(JsonNode p) {
        String tourism = text(p, "tourism");
        String amenity = text(p, "amenity");
        if (tourism != null) {
            return switch (tourism) {
                case "hotel", "hostel", "guest_house" -> "HOTEL";
                case "information" -> "SERVICE";
                default -> "ATTRACTION";
            };
        }
        if (amenity != null) {
            return switch (amenity) {
                case "restaurant", "cafe", "fast_food", "bar" -> "CATERING";
                case "parking" -> "PARKING";
                case "toilets" -> "TOILET";
                default -> "SERVICE";
            };
        }
        if (p.has("shop")) {
            return "RETAIL";
        }
        return p.has("public_transport") ? "TRANSPORT" : null;
    }

    private static String text(JsonNode node, String... fields) {
        for (String f : fields) {
            JsonNode v = node.get(f);
            if (v != null && !v.isNull() && !v.asString().isBlank()) {
                return v.asString();
            }
        }
        return null;
    }

    private static double[] centroid(JsonNode geometry) {
        JsonNode c = geometry.get("coordinates");
        while (c != null && c.isArray() && c.size() > 0 && c.get(0).isArray() && c.get(0).get(0).isArray()) {
            c = c.get(0);
        }
        if (c == null || !c.isArray() || c.size() == 0) {
            return null;
        }
        if (!c.get(0).isArray()) {
            return new double[]{c.get(0).asDouble(), c.get(1).asDouble()};
        }
        double x = 0;
        double y = 0;
        for (JsonNode pt : c) {
            x += pt.get(0).asDouble();
            y += pt.get(1).asDouble();
        }
        return new double[]{x / c.size(), y / c.size()};
    }

    private void weather(DemoSeedContext ctx) throws IOException {
        Path file = ctx.dataDir().resolve("weather/harbin_daily.json");
        JsonNode daily = JSON.readTree(Files.readString(file)).get("daily");
        JsonNode time = daily.get("time");
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < time.size(); i++) {
            rows.add(new Object[]{LocalDate.parse(time.get(i).asString()), num(daily, "temperature_2m_max", i),
                    num(daily, "temperature_2m_min", i), num(daily, "temperature_2m_mean", i), num(daily, "precipitation_sum", i),
                    num(daily, "snowfall_sum", i), num(daily, "wind_speed_10m_max", i), num(daily, "weather_code", i)});
        }
        for (Object[] r : rows) {
            if (r[3] == null) {
                continue;
            }
            ctx.jdbc().sql("""
                    INSERT INTO weather_daily (location_code, biz_date, temp_max, temp_min, temp_mean, precipitation, snowfall,
                        wind_max, weather_code, is_forecast, source)
                    VALUES ('harbin', ?, ?, ?, ?, ?, ?, ?, ?, FALSE, 'open-meteo-archive') ON CONFLICT DO NOTHING
                    """).params(r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7] == null ? null : ((Double) r[7]).intValue()).update();
        }
        log.info("导入哈尔滨逐日气象 {} 天", rows.size());
    }

    private static Double num(JsonNode daily, String field, int i) {
        JsonNode v = daily.get(field).get(i);
        return v == null || v.isNull() ? null : v.asDouble();
    }

    private void holidays(DemoSeedContext ctx) throws IOException {
        try (var files = Files.list(ctx.dataDir().resolve("holiday"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                JsonNode root = JSON.readTree(Files.readString(file));
                String source = root.has("papers") && root.get("papers").size() > 0 ? root.get("papers").get(0).asString() : "holiday-cn";
                for (JsonNode d : root.get("days")) {
                    ctx.jdbc().sql("INSERT INTO holiday_calendar (biz_date, name, is_off_day, source) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING")
                            .params(LocalDate.parse(d.get("date").asString()), d.get("name").asString(), d.get("isOffDay").asBoolean(),
                                    source.substring(0, Math.min(source.length(), 200))).update();
                }
            }
        }
    }
}
