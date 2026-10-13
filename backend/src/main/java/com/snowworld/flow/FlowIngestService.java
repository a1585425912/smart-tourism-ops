package com.snowworld.flow;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 客流统一接入入口: 真实采集、CSV 导入与演示适配器都经过这里写入 flow_event。
 *
 * <p>幂等: (source_id, source_record_id) 唯一, 重复记录不重复计数;
 * 迟到数据: 写入后按受影响的时间窗口从事件重算小时/日汇总。
 */
@Service
public class FlowIngestService {

    public record FlowRecord(String sourceRecordId, long siteId, Long zoneId, Long deviceId, String metric,
                             BigDecimal value, OffsetDateTime bizTime, String quality) {
    }

    public record IngestResult(int total, int inserted, int duplicates) {
    }

    private static final String INSERT = """
            INSERT INTO flow_event (source_id, source_record_id, site_id, zone_id, device_id, metric, value, biz_time, quality, data_mode)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (source_id, source_record_id) DO NOTHING
            """;

    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbc;
    private final Clock clock;

    public FlowIngestService(JdbcTemplate jdbcTemplate, JdbcClient jdbc, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public IngestResult ingest(long sourceId, String dataMode, List<FlowRecord> records) {
        return ingest(sourceId, dataMode, records, true);
    }

    /** rebuild=false 时调用方负责稍后调用 {@link #rebuildAggregates}(批量初始化时使用)。 */
    @Transactional
    public IngestResult ingest(long sourceId, String dataMode, List<FlowRecord> records, boolean rebuild) {
        if (records.isEmpty()) {
            return new IngestResult(0, 0, 0);
        }
        int[][] counts = jdbcTemplate.batchUpdate(INSERT, records, 1000, (ps, r) -> {
            ps.setLong(1, sourceId);
            ps.setString(2, r.sourceRecordId());
            ps.setLong(3, r.siteId());
            ps.setObject(4, r.zoneId());
            ps.setObject(5, r.deviceId());
            ps.setString(6, r.metric());
            ps.setBigDecimal(7, r.value());
            ps.setObject(8, r.bizTime());
            ps.setString(9, r.quality() == null ? "GOOD" : r.quality());
            ps.setString(10, dataMode);
        });
        int inserted = 0;
        for (int[] batch : counts) {
            for (int c : batch) {
                inserted += Math.max(c, 0);
            }
        }
        if (rebuild && inserted > 0) {
            Map<Long, OffsetDateTime[]> windows = new HashMap<>();
            for (FlowRecord r : records) {
                OffsetDateTime[] w = windows.computeIfAbsent(r.siteId(), k -> new OffsetDateTime[]{r.bizTime(), r.bizTime()});
                if (r.bizTime().isBefore(w[0])) {
                    w[0] = r.bizTime();
                }
                if (r.bizTime().isAfter(w[1])) {
                    w[1] = r.bizTime();
                }
            }
            windows.forEach((siteId, w) -> rebuildAggregates(siteId, w[0], w[1].plusSeconds(1)));
        }
        OffsetDateTime latest = records.stream().map(FlowRecord::bizTime).max(OffsetDateTime::compareTo).orElseThrow();
        jdbc.sql("""
                UPDATE integration_source SET last_success_at = ?, health = 'OK', last_error = NULL,
                    watermark = GREATEST(coalesce(watermark, ?), ?) WHERE id = ?
                """).params(OffsetDateTime.now(clock), latest, latest, sourceId).update();
        return new IngestResult(records.size(), inserted, records.size() - inserted);
    }

    /** 从事件重算 [from, to) 覆盖到的小时桶与业务日桶(Asia/Shanghai)。排队类指标按设备查询, 不进入汇总表。 */
    @Transactional
    public void rebuildAggregates(long siteId, OffsetDateTime from, OffsetDateTime to) {
        // 业务日按 Asia/Shanghai(固定 +08:00)截断
        from = from.withOffsetSameInstant(ZoneOffset.ofHours(8));
        to = to.withOffsetSameInstant(ZoneOffset.ofHours(8));
        OffsetDateTime hourFrom = from.truncatedTo(ChronoUnit.HOURS);
        OffsetDateTime hourTo = to.truncatedTo(ChronoUnit.HOURS).plusHours(1);
        rebuild(siteId, "HOUR", "date_trunc('hour', biz_time)", hourFrom, hourTo);
        OffsetDateTime dayFrom = from.truncatedTo(ChronoUnit.DAYS);
        OffsetDateTime dayTo = to.truncatedTo(ChronoUnit.DAYS).plusDays(1);
        rebuild(siteId, "DAY", "date_trunc('day', biz_time, 'Asia/Shanghai')", dayFrom, dayTo);
    }

    private void rebuild(long siteId, String granularity, String bucketExpr, OffsetDateTime from, OffsetDateTime to) {
        jdbc.sql("DELETE FROM flow_aggregate WHERE site_id = ? AND granularity = ? AND bucket_start >= ? AND bucket_start < ?")
                .params(siteId, granularity, from, to).update();
        jdbc.sql("""
                INSERT INTO flow_aggregate (site_id, zone_id, metric, granularity, bucket_start, sum_value, max_value,
                    avg_value, last_value, sample_count, data_mode)
                SELECT site_id, coalesce(zone_id, 0), metric, ?, %s AS bucket, sum(value), max(value), round(avg(value), 2),
                       (array_agg(value ORDER BY biz_time DESC))[1], count(*),
                       CASE WHEN count(DISTINCT data_mode) = 1 THEN min(data_mode) ELSE 'MIXED' END
                FROM flow_event
                WHERE site_id = ? AND biz_time >= ? AND biz_time < ? AND metric IN ('ENTRY', 'EXIT', 'IN_PARK', 'ZONE_COUNT')
                GROUP BY site_id, coalesce(zone_id, 0), metric, bucket
                """.formatted(bucketExpr)).params(granularity, siteId, from, to).update();
    }
}
