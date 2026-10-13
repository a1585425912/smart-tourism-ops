package com.snowworld.common;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 统一业务时钟。演示环境可配置回放时间 snow.demo.replay-at, 系统从该时刻开始按真实速度走时,
 * 便于在非冰雪季展示冬季运营场景; 回放状态通过 /api/v1/system/info 暴露并在界面标注。
 */
@Configuration
public class ClockConfig {

    private static final Logger log = LoggerFactory.getLogger(ClockConfig.class);
    public static final ZoneId BIZ_ZONE = ZoneId.of("Asia/Shanghai");

    /** 执行器使用真实时间维护租约，展示给运营者时转换到统一的业务回放时间。 */
    public static OffsetDateTime executionTime(OffsetDateTime at, Clock clock, boolean replay) {
        if (at == null || !replay) return at;
        return at.plus(Duration.between(java.time.Instant.now(), clock.instant()));
    }

    @Bean
    public Clock clock(SnowProperties props) {
        Clock system = Clock.system(BIZ_ZONE);
        OffsetDateTime replayAt = props.demo().replayAt();
        if (replayAt == null) {
            return system;
        }
        Duration offset = Duration.between(OffsetDateTime.now(system), replayAt);
        log.warn("演示回放时钟已启用: 业务时间从 {} 开始", replayAt);
        return Clock.offset(system, offset);
    }
}
