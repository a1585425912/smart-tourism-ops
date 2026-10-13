package com.snowworld.common;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 事实类数据的来源元信息, 随数据一起返回给前端与 Agent。
 *
 * @param dataAsOf  数据截止时间, 无数据时为 null
 * @param sourceIds 来源编码, 可通过接入状态接口解析
 * @param dataMode  REAL / DEMO / MIXED
 * @param quality   GOOD / STALE / PARTIAL / MISSING
 */
public record DataMeta(OffsetDateTime dataAsOf, List<String> sourceIds, String dataMode, String quality) {

    public static DataMeta missing(List<String> sourceIds, String dataMode) {
        return new DataMeta(null, sourceIds, dataMode, "MISSING");
    }

    public static String mergeMode(List<String> modes) {
        if (modes.isEmpty()) {
            return "DEMO";
        }
        return modes.stream().distinct().count() == 1 ? modes.getFirst() : "MIXED";
    }
}
