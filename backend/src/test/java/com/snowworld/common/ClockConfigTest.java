package com.snowworld.common;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class ClockConfigTest {
    @Test void executionTimestampsFollowReplayClock() {
        Instant replay = Instant.parse("2026-02-18T06:30:00Z");
        Clock clock = Clock.fixed(replay, ClockConfig.BIZ_ZONE);
        OffsetDateTime physical = OffsetDateTime.now();
        OffsetDateTime shown = ClockConfig.executionTime(physical, clock, true);
        assertTrue(Duration.between(replay, shown.toInstant()).abs().compareTo(Duration.ofSeconds(1)) < 0);
        OffsetDateTime earlier = ClockConfig.executionTime(physical.minusMinutes(2), clock, true);
        assertTrue(Duration.between(earlier, shown).minusMinutes(2).abs().compareTo(Duration.ofSeconds(1)) < 0);
    }

    @Test void realModePreservesExecutionTimestampsAndNulls() {
        OffsetDateTime physical = OffsetDateTime.now();
        assertSame(physical, ClockConfig.executionTime(physical, Clock.systemUTC(), false));
        assertNull(ClockConfig.executionTime(null, Clock.systemUTC(), true));
    }
}
