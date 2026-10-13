package com.snowworld.demo;

import com.snowworld.common.ClockConfig;
import com.snowworld.common.SnowProperties;
import com.snowworld.flow.FlowIngestService;
import com.snowworld.flow.FlowIngestService.FlowRecord;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 客流演示适配器: 把 {@link DemoFlowModel} 的仿真结果经正式接入入口 {@link FlowIngestService} 写入正式数据模型。
 * 首次运行回灌历史(较早日期按小时、最近两天按 10 分钟), 之后每分钟补齐到业务时钟的"当前", 模拟实时采集。
 * 替换为真实闸机/客流系统时, 新增一个调用 FlowIngestService 的适配器并停用本类对应的数据源即可。
 */
@Component
public class DemoFlowFeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoFlowFeeder.class);
    private static final int ZONE_HISTORY_DAYS = 45;

    private record Zone(long id, int capacity) {
    }

    private record QueuePoint(long deviceId, int zoneIndex, double serviceRate, int ordinal, boolean measured) {
    }

    private record Site(long id, String code, long sourceId, List<Zone> zones, List<QueuePoint> queues) {
    }

    private final JdbcClient jdbc;
    private final DemoFlowModel model;
    private final FlowIngestService ingest;
    private final SnowProperties props;
    private final Clock clock;

    public DemoFlowFeeder(JdbcClient jdbc, DemoFlowModel model, FlowIngestService ingest, SnowProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.model = model;
        this.ingest = ingest;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 20_000)
    public void tick() {
        if (!props.demo().seed()) {
            return;
        }
        try {
            sync(false, null);
        } catch (RuntimeException e) {
            log.warn("演示客流补齐失败: {}", e.getMessage());
            jdbc.sql("UPDATE integration_source SET last_error_at = now(), last_error = ?, health = 'FAILED' WHERE code LIKE 'flow-demo-%'")
                    .param(String.valueOf(e.getMessage())).update();
        }
    }

    /**
     * 把各演示景区的数据补齐到业务时钟当前时刻。
     *
     * @param bulk        首次回灌: 写入后统一重算汇总
     * @param historyFrom 没有任何数据时的起始日期
     */
    public synchronized void sync(boolean bulk, LocalDate historyFrom) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        // 当日已结束的时间片数量: 一个时间片结束后其数据才"到达"
        int completedToday = (now.getHour() * 60 + now.getMinute()) / DemoFlowModel.SLOT_MINUTES;
        for (Site site : loadSites()) {
            dropFuture(site, now);
            OffsetDateTime last = jdbc.sql("SELECT max(biz_time) FROM flow_event WHERE source_id = ? AND metric = 'IN_PARK'")
                    .param(site.sourceId()).query(OffsetDateTime.class).optional().orElse(null);
            LocalDate date;
            int fromSlot;
            if (last == null) {
                if (historyFrom == null) {
                    continue;
                }
                date = historyFrom;
                fromSlot = 0;
            } else {
                LocalDateTime local = last.atZoneSameInstant(ClockConfig.BIZ_ZONE).toLocalDateTime();
                date = local.toLocalDate();
                fromSlot = (local.getHour() * 60 + local.getMinute()) / DemoFlowModel.SLOT_MINUTES + 1;
            }
            List<FlowRecord> records = new ArrayList<>();
            OffsetDateTime windowStart = null;
            for (; !date.isAfter(today); date = date.plusDays(1), fromSlot = 0) {
                int toSlot = date.equals(today) ? completedToday : DemoFlowModel.SLOTS;
                if (fromSlot >= toSlot) {
                    continue;
                }
                if (windowStart == null) {
                    windowStart = date.atStartOfDay(ClockConfig.BIZ_ZONE).toOffsetDateTime();
                }
                boolean hourly = date.isBefore(today.minusDays(1)) && fromSlot % 6 == 0;
                boolean zones = !date.isBefore(today.minusDays(ZONE_HISTORY_DAYS));
                emitDay(site, date, fromSlot, toSlot, hourly, zones, records);
            }
            if (records.isEmpty()) {
                continue;
            }
            ingest.ingest(site.sourceId(), "DEMO", records, !bulk);
            if (bulk) {
                ingest.rebuildAggregates(site.id(), windowStart, now);
                log.info("演示客流回灌 {}: {} 条事件", site.code(), records.size());
            }
        }
    }

    /** 回放时钟每次启动都回到同一时刻, 需清掉上次运行留下的"未来"数据, 保证当前时刻之后没有数据。 */
    private void dropFuture(Site site, OffsetDateTime now) {
        int deleted = jdbc.sql("DELETE FROM flow_event WHERE source_id = ? AND biz_time > ?").params(site.sourceId(), now).update();
        if (deleted > 0) {
            ingest.rebuildAggregates(site.id(), now.minusHours(1), now.plusDays(3));
            jdbc.sql("UPDATE integration_source SET watermark = ? WHERE id = ?").params(now, site.sourceId()).update();
            log.info("回放时钟重置: 清理 {} 的 {} 条未来演示数据", site.code(), deleted);
        }
    }

    private void emitDay(Site site, LocalDate date, int fromSlot, int toSlot, boolean hourly, boolean withZones,
                         List<FlowRecord> out) {
        DemoFlowModel.DayCurve curve = model.curve(site.code(), date);
        if (curve == null) {
            return;
        }
        int[] capacities = site.zones().stream().mapToInt(Zone::capacity).toArray();
        int step = hourly ? 6 : 1;
        for (int s = Math.max(fromSlot, curve.firstSlot() / step * step); s < toSlot && s <= curve.lastSlot(); s += step) {
            int end = Math.min(s + step, toSlot) - 1;
            long entries = 0;
            long exits = 0;
            for (int k = s; k <= end; k++) {
                entries += curve.entries()[k];
                exits += curve.exits()[k];
            }
            OffsetDateTime start = slotTime(date, s);
            OffsetDateTime stockAt = slotTime(date, end);
            String tag = (hourly ? "H:" : "T:") + start.toEpochSecond() / 60;
            long inPark = curve.inPark()[end];
            out.add(record("ENTRY:" + tag, site, null, null, "ENTRY", entries, start));
            out.add(record("EXIT:" + tag, site, null, null, "EXIT", exits, start));
            out.add(record("IN_PARK:" + tag, site, null, null, "IN_PARK", inPark, stockAt));
            if (!withZones || capacities.length == 0) {
                continue;
            }
            long[] counts = model.zoneCounts(site.code(), date, end, inPark, capacities);
            for (int i = 0; i < counts.length; i++) {
                out.add(record("Z" + site.zones().get(i).id() + ":" + tag, site, site.zones().get(i).id(), null,
                        "ZONE_COUNT", counts[i], stockAt));
            }
            if (hourly) {
                continue;
            }
            for (QueuePoint q : site.queues()) {
                long len = model.queueLength(site.code(), date, end, counts[q.zoneIndex()], q.ordinal());
                Long zoneId = site.zones().get(q.zoneIndex()).id();
                out.add(record("QL" + q.deviceId() + ":" + tag, site, zoneId, q.deviceId(), "QUEUE_LEN", len, stockAt));
                if (q.measured()) {
                    BigDecimal wait = BigDecimal.valueOf(len / q.serviceRate() * model.measuredFactor(site.code(), date, end, q.ordinal()))
                            .max(BigDecimal.ZERO).setScale(1, RoundingMode.HALF_UP);
                    out.add(new FlowRecord("QW" + q.deviceId() + ":" + tag, site.id(), zoneId, q.deviceId(), "QUEUE_WAIT",
                            wait, stockAt, "GOOD"));
                }
            }
        }
    }

    private static FlowRecord record(String id, Site site, Long zoneId, Long deviceId, String metric, long value, OffsetDateTime at) {
        return new FlowRecord(id, site.id(), zoneId, deviceId, metric, BigDecimal.valueOf(value), at, "GOOD");
    }

    private static OffsetDateTime slotTime(LocalDate date, int slot) {
        return date.atStartOfDay(ClockConfig.BIZ_ZONE).plusMinutes((long) slot * DemoFlowModel.SLOT_MINUTES).toOffsetDateTime();
    }

    private List<Site> loadSites() {
        List<Site> sites = new ArrayList<>();
        jdbc.sql("""
                SELECT s.id, s.code, src.id AS source_id FROM scenic_site s
                JOIN integration_source src ON src.site_id = s.id AND src.source_type = 'FLOW' AND src.data_mode = 'DEMO' AND src.enabled
                ORDER BY s.id
                """).query((rs, i) -> new Site(rs.getLong("id"), rs.getString("code"), rs.getLong("source_id"), new ArrayList<>(), new ArrayList<>()))
                .list().stream().filter(s -> model.supports(s.code())).forEach(sites::add);
        for (Site site : sites) {
            site.zones().addAll(jdbc.sql("SELECT id, coalesce(capacity, 1000) AS capacity FROM scenic_zone WHERE site_id = ? AND active ORDER BY id")
                    .param(site.id()).query((rs, i) -> new Zone(rs.getLong("id"), rs.getInt("capacity"))).list());
            List<Long> zoneIds = site.zones().stream().map(Zone::id).toList();
            int[] ordinal = {0};
            jdbc.sql("SELECT device_id, zone_id, service_rate_per_min FROM queue_point WHERE site_id = ? AND active AND device_id IS NOT NULL ORDER BY id")
                    .param(site.id()).query((rs, i) -> {
                        int zoneIndex = zoneIds.indexOf(rs.getLong("zone_id"));
                        int ord = ordinal[0]++;
                        return zoneIndex < 0 ? null
                                : new QueuePoint(rs.getLong("device_id"), zoneIndex, rs.getDouble("service_rate_per_min"), ord, ord % 2 == 0);
                    }).list().stream().filter(q -> q != null).forEach(site.queues()::add);
        }
        return sites;
    }
}
