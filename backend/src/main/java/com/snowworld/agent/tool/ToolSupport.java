package com.snowworld.agent.tool;

import com.snowworld.agent.AgentJson;
import com.snowworld.agent.tool.AgentTool.ToolException;
import com.snowworld.common.TimeRange;
import com.snowworld.scenic.SiteMapper;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 工具参数的 Schema 构造、服务端二次校验, 以及景区/时间参数解析。 */
@Component
public class ToolSupport {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    public static final List<String> PERIODS = List.of("TODAY", "YESTERDAY", "THIS_WEEK", "LAST_7_DAYS", "LAST_30_DAYS", "THIS_MONTH");

    /** 解析后的时间段及其中文描述。 */
    public record Period(TimeRange range, String label) {
        public LocalDate startDate() {
            return range.start().atZoneSameInstant(ZONE).toLocalDate();
        }

        public LocalDate endDateExclusive() {
            return range.end().atZoneSameInstant(ZONE).toLocalDate();
        }
    }

    private final JdbcClient jdbc;

    public ToolSupport(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------- Schema ----------------

    public static ObjectNode schema(String... required) {
        ObjectNode s = AgentJson.object();
        s.put("type", "object");
        s.putObject("properties");
        ArrayNode req = s.putArray("required");
        for (String r : required) {
            req.add(r);
        }
        s.put("additionalProperties", false);
        return s;
    }

    public static ObjectNode prop(ObjectNode schema, String name, String type, String description) {
        ObjectNode p = ((ObjectNode) schema.get("properties")).putObject(name);
        p.put("type", type);
        p.put("description", description);
        return p;
    }

    public static void enumProp(ObjectNode schema, String name, String description, List<String> values) {
        ArrayNode e = prop(schema, name, "string", description).putArray("enum");
        values.forEach(e::add);
    }

    public static void siteProp(ObjectNode schema) {
        prop(schema, "site", "string", "景区名称或编码, 必须是系统提示中列出的可访问景区; 只有一个可访问景区时可省略");
    }

    public static void timeProps(ObjectNode schema) {
        enumProp(schema, "period", "相对时间段(按当前业务时间计算)。与 startDate/endDate、holiday 三选一", PERIODS);
        prop(schema, "startDate", "string", "开始日期 YYYY-MM-DD(含)");
        prop(schema, "endDate", "string", "结束日期 YYYY-MM-DD(含)");
        prop(schema, "holiday", "string", "法定节假日名称, 如 春节、国庆节; 取最近一次该假期的放假日期");
    }

    /** 按 Schema 校验模型给出的参数: 必填、类型、枚举、未知字段、长度。 */
    public static void validate(ObjectNode schema, JsonNode args) {
        if (args == null || !args.isObject()) {
            throw ToolException.invalid("参数必须是 JSON 对象");
        }
        JsonNode props = schema.get("properties");
        for (String name : args.propertyNames()) {
            if (!props.has(name)) {
                throw ToolException.invalid("不支持的参数: " + name);
            }
        }
        for (JsonNode r : schema.get("required")) {
            JsonNode v = args.get(r.asString());
            if (v == null || v.isNull() || (v.isString() && v.asString().isBlank())) {
                throw ToolException.invalid("缺少必填参数: " + r.asString());
            }
        }
        for (Map.Entry<String, JsonNode> e : args.properties()) {
            JsonNode v = e.getValue();
            if (v.isNull()) {
                continue;
            }
            JsonNode p = props.get(e.getKey());
            String type = p.get("type").asString();
            boolean ok = switch (type) {
                case "string" -> v.isString();
                case "integer" -> v.isIntegralNumber() || (v.isString() && v.asString().matches("-?\\d{1,9}"));
                case "number" -> v.isNumber();
                case "boolean" -> v.isBoolean();
                case "array" -> v.isArray();
                default -> false;
            };
            if (!ok) {
                throw ToolException.invalid("参数 " + e.getKey() + " 类型应为 " + type);
            }
            if (v.isString() && v.asString().length() > 500) {
                throw ToolException.invalid("参数 " + e.getKey() + " 过长");
            }
            if (v.isArray() && v.size() > 20) {
                throw ToolException.invalid("参数 " + e.getKey() + " 元素过多(最多 20 个)");
            }
            if (p.has("enum")) {
                boolean in = false;
                for (JsonNode ev : p.get("enum")) {
                    in |= ev.asString().equals(v.asString());
                }
                if (!in) {
                    throw ToolException.invalid("参数 " + e.getKey() + " 取值不合法, 可选: " + p.get("enum"));
                }
            }
        }
    }

    // ---------------- 景区 ----------------

    /**
     * 解析景区参数。模型指定了授权范围之外的景区时返回 DENIED, 绝不静默改查其它景区。
     *
     * @return 景区; 未指定且 required=false 时返回 null(表示全部授权景区)
     */
    public static SiteMapper.SiteRow resolveSite(ToolContext ctx, JsonNode args, boolean required) {
        if (ctx.allowedSites().isEmpty()) {
            throw ToolException.denied("当前用户没有任何景区的数据权限");
        }
        String s = AgentJson.text(args, "site");
        if (s == null || s.isBlank()) {
            if (ctx.allowedSites().size() == 1) {
                return ctx.site(ctx.allowedSites().getFirst());
            }
            if (required) {
                throw ToolException.invalid("请用 site 参数指定景区, 可访问景区: " + ctx.allowedSiteNames());
            }
            return null;
        }
        String q = s.strip();
        SiteMapper.SiteRow match = null;
        for (SiteMapper.SiteRow site : ctx.allSites()) {
            if (String.valueOf(site.getId()).equals(q) || site.getCode().equalsIgnoreCase(q)
                    || site.getName().equals(q) || site.getShortName().equals(q)) {
                match = site;
                break;
            }
        }
        if (match == null) {
            for (SiteMapper.SiteRow site : ctx.allSites()) {
                if (site.getName().contains(q) || q.contains(site.getShortName()) || q.contains(site.getName())) {
                    match = site;
                    break;
                }
            }
        }
        if (match == null) {
            throw ToolException.invalid("未找到景区「" + AgentJson.truncate(q, 30) + "」, 可访问景区: " + ctx.allowedSiteNames());
        }
        if (!ctx.allowedSites().contains(match.getId())) {
            throw ToolException.denied("当前用户无权访问景区「" + match.getName() + "」的数据");
        }
        return match;
    }

    /** 未指定景区时返回全部授权景区。 */
    public static List<Long> resolveSites(ToolContext ctx, JsonNode args) {
        SiteMapper.SiteRow site = resolveSite(ctx, args, false);
        return site == null ? ctx.allowedSites() : List.of(site.getId());
    }

    public static String sitesLabel(ToolContext ctx, List<Long> siteIds) {
        return siteIds.stream().map(id -> ctx.site(id).getShortName()).reduce((a, b) -> a + "、" + b).orElse("");
    }

    // ---------------- 时间 ----------------

    public Period resolvePeriod(ToolContext ctx, JsonNode args, String defaultPeriod, int maxDays) {
        LocalDate today = ctx.now().atZoneSameInstant(ZONE).toLocalDate();
        String startText = AgentJson.text(args, "startDate");
        String endText = AgentJson.text(args, "endDate");
        String holiday = AgentJson.text(args, "holiday");
        String period = AgentJson.text(args, "period");
        LocalDate start;
        LocalDate endExclusive;
        String label;
        if (startText != null || endText != null) {
            if (startText == null || endText == null) {
                throw ToolException.invalid("startDate 与 endDate 必须同时提供");
            }
            start = parseDate(startText, "startDate");
            endExclusive = parseDate(endText, "endDate").plusDays(1);
            label = start.equals(endExclusive.minusDays(1)) ? start.toString() : start + " 至 " + endExclusive.minusDays(1);
        } else if (holiday != null) {
            List<LocalDate> days = jdbc.sql("""
                    SELECT biz_date FROM holiday_calendar
                    WHERE name = ? AND is_off_day AND extract(year FROM biz_date) = (
                        SELECT max(extract(year FROM biz_date)) FROM holiday_calendar
                        WHERE name = ? AND is_off_day AND biz_date <= ?)
                    ORDER BY biz_date
                    """).params(holiday.strip(), holiday.strip(), today).query(LocalDate.class).list();
            if (days.isEmpty()) {
                throw ToolException.invalid("节假日日历中没有「" + AgentJson.truncate(holiday, 20) + "」的记录");
            }
            start = days.getFirst();
            endExclusive = days.getLast().plusDays(1);
            label = start.getYear() + "年" + holiday.strip() + "假期(" + start + " 至 " + days.getLast() + ")";
        } else {
            String p = period == null ? defaultPeriod : period;
            switch (p) {
                case "YESTERDAY" -> {
                    start = today.minusDays(1);
                    endExclusive = today;
                    label = "昨天(" + start + ")";
                }
                case "THIS_WEEK" -> {
                    start = today.with(DayOfWeek.MONDAY);
                    endExclusive = today.plusDays(1);
                    label = "本周(" + start + " 至 " + today + ")";
                }
                case "LAST_7_DAYS" -> {
                    start = today.minusDays(6);
                    endExclusive = today.plusDays(1);
                    label = "近7天(" + start + " 至 " + today + ")";
                }
                case "LAST_30_DAYS" -> {
                    start = today.minusDays(29);
                    endExclusive = today.plusDays(1);
                    label = "近30天(" + start + " 至 " + today + ")";
                }
                case "THIS_MONTH" -> {
                    start = today.withDayOfMonth(1);
                    endExclusive = today.plusDays(1);
                    label = "本月(" + start + " 至 " + today + ")";
                }
                default -> {
                    start = today;
                    endExclusive = today.plusDays(1);
                    label = "今天(" + today + ")";
                }
            }
        }
        if (!endExclusive.isAfter(start)) {
            throw ToolException.invalid("结束日期不能早于开始日期");
        }
        if (Duration.between(start.atStartOfDay(), endExclusive.atStartOfDay()).toDays() > maxDays) {
            throw ToolException.invalid("时间范围不能超过 " + maxDays + " 天");
        }
        TimeRange range = new TimeRange(start.atStartOfDay(ZONE).toOffsetDateTime(), endExclusive.atStartOfDay(ZONE).toOffsetDateTime());
        return new Period(range, label);
    }

    private static LocalDate parseDate(String s, String name) {
        try {
            return LocalDate.parse(s.strip().substring(0, Math.min(10, s.strip().length())));
        } catch (DateTimeParseException e) {
            throw ToolException.invalid(name + " 需为 YYYY-MM-DD 格式");
        }
    }

    public static OffsetDateTime earliest(OffsetDateTime a, OffsetDateTime b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isBefore(b) ? a : b;
    }
}
