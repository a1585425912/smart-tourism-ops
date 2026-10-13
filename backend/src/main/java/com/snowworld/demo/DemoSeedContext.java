package com.snowworld.demo;

import com.snowworld.common.SnowProperties;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Random;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 演示数据生成上下文。随机数使用固定种子, 保证每次重建的数据一致、可复现。
 *
 * @param realNow 真实当前时间(历史数据生成到此为止); 业务回放时钟见 {@link #clock}
 */
public record DemoSeedContext(JdbcClient jdbc, SnowProperties props, Clock clock, Random random,
                              OffsetDateTime realNow, LocalDate historyFrom) {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    public Path dataDir() {
        return Path.of(props.dataDir()).toAbsolutePath().normalize();
    }

    public long siteId(String code) {
        return jdbc.sql("SELECT id FROM scenic_site WHERE code = ?").param(code).query(Long.class).single();
    }

    public long userId(String username) {
        return jdbc.sql("SELECT id FROM app_user WHERE username = ?").param(username).query(Long.class).single();
    }

    public long sourceId(String code) {
        return jdbc.sql("SELECT id FROM integration_source WHERE code = ?").param(code).query(Long.class).single();
    }

    /** 新建一个演示数据源(已存在则返回已有 id)。 */
    public long ensureSource(String code, String name, String sourceType, Long siteId, String provider,
                             String licenseNote, Integer refreshSeconds) {
        jdbc.sql("""
                INSERT INTO integration_source (code, name, source_type, data_mode, site_id, provider, license_note,
                    refresh_seconds, health, last_success_at, watermark)
                VALUES (?, ?, ?, 'DEMO', ?, ?, ?, ?, 'OK', ?, ?)
                ON CONFLICT (code) DO NOTHING
                """)
                .params(code, name, sourceType, siteId, provider, licenseNote, refreshSeconds,
                        OffsetDateTime.now(clock), OffsetDateTime.now(clock))
                .update();
        return sourceId(code);
    }
}
