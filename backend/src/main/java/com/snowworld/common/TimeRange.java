package com.snowworld.common;

import java.time.Duration;
import java.time.OffsetDateTime;

/** 左闭右开时间段 [start, end)。 */
public record TimeRange(OffsetDateTime start, OffsetDateTime end) {

    public TimeRange {
        if (start == null || end == null) {
            throw ApiException.badRequest("start 和 end 不能为空");
        }
        if (!end.isAfter(start)) {
            throw ApiException.badRequest("end 必须晚于 start");
        }
    }

    public TimeRange limit(Duration max) {
        if (Duration.between(start, end).compareTo(max) > 0) {
            throw new ApiException(ErrorCode.RANGE_TOO_LARGE, "时间范围不能超过 " + max.toDays() + " 天");
        }
        return this;
    }
}
