package com.snowworld.flow;

import com.snowworld.common.DataMeta;
import com.snowworld.common.TimeRange;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 客流查询契约, 供 API、大屏、运营日报与 Agent 工具共用, 保证各处口径一致。
 *
 * <p>口径: ENTRY/EXIT 为时段内人次(闸机计次, 未去重, 不等于独立游客数); IN_PARK 为在园存量(取来源上报的存量值);
 * ZONE_COUNT 为区域瞬时人数(来自区域绑定的计数设备, 围栏本身不产生人数); 区域人数不可直接相加得到景区人数。
 * 缺失值返回 null, 不转换为 0。
 */
public interface FlowQueryService {

    /** 景区在时间段 [start, end) 内的客流汇总。 */
    FlowSummary summary(long siteId, TimeRange range);

    /**
     * 时间序列。zoneId 为 null 表示景区整体; granularity 为 HOUR 或 DAY。
     * HOUR 粒度最多 31 天, DAY 粒度最多 400 天。
     */
    FlowTrend trend(long siteId, Long zoneId, String metric, String granularity, TimeRange range);

    /** 各区域最新人数与承载率(热度)。 */
    List<ZoneLoad> zoneLoads(long siteId);

    record FlowSummary(
            long siteId,
            String siteName,
            OffsetDateTime start,
            OffsetDateTime end,
            Long entries,
            Long exits,
            Long inParkCurrent,
            Long inParkPeak,
            OffsetDateTime inParkPeakAt,
            Integer capacity,
            /** 当前在园 / 最大承载量, 无在园数据时为 null */
            BigDecimal utilization,
            /** NORMAL / WARN(>=预警比例) / FULL(>=100%) / UNKNOWN */
            String loadLevel,
            /** 同比上一年同期入园人次变化率, 不可计算时为 null 并在 notes 中说明 */
            BigDecimal entriesYoy,
            BigDecimal entriesDod,
            List<String> notes,
            DataMeta meta) {
    }

    record TrendPoint(OffsetDateTime t, BigDecimal value) {
    }

    record Gap(OffsetDateTime start, OffsetDateTime end) {
    }

    record FlowTrend(
            long siteId,
            Long zoneId,
            String metric,
            String granularity,
            /** sum / max / last: 说明 value 的聚合方式 */
            String aggregation,
            List<TrendPoint> points,
            List<Gap> gaps,
            DataMeta meta) {
    }

    record ZoneLoad(
            long zoneId,
            String zoneName,
            String zoneType,
            Long count,
            Integer capacity,
            BigDecimal ratio,
            /** LOW / NORMAL / WARN / FULL / UNKNOWN; 规则: ratio 小于 0.3 LOW, 小于预警比例 NORMAL, 小于 1 WARN, 否则 FULL */
            String level,
            OffsetDateTime at,
            String countSource,
            DataMeta meta) {
    }
}
