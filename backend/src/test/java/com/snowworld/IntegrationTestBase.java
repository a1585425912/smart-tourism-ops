package com.snowworld;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * 集成测试基类: 连接真实 PostgreSQL/PostGIS 测试库(application-test.yml, 可用 SNOW_TEST_DB_URL 覆盖)。
 * 每个测试类开始前清空业务表(保留迁移记录、角色字典与 PostGIS 系统表), 保证互不影响。
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    void resetDatabase(JdbcClient jdbcClient) {
        String tables = jdbcClient.sql("""
                SELECT string_agg(quote_ident(tablename), ', ') FROM pg_tables
                WHERE schemaname = 'public' AND tablename NOT IN ('flyway_schema_history', 'spatial_ref_sys', 'role')
                """).query(String.class).single();
        jdbcClient.sql("TRUNCATE " + tables + " RESTART IDENTITY CASCADE").update();
    }

    /** 创建一个最小景区(含简单矩形边界), 返回 id。 */
    protected long createSite(String code, String name) {
        return jdbc.sql("""
                INSERT INTO scenic_site (code, name, short_name, capacity, center, boundary)
                VALUES (?, ?, ?, 10000, ST_SetSRID(ST_Point(126.60, 45.78), 4326),
                        ST_Multi(ST_MakeEnvelope(126.59, 45.77, 126.61, 45.79, 4326)))
                RETURNING id
                """).params(code, name, name).query(Long.class).single();
    }

    /** 创建用户(密码 test-password-123), 指定角色与景区范围, 返回 id。 */
    protected long createUser(String username, String role, Long... siteIds) {
        long id = jdbc.sql("""
                INSERT INTO app_user (username, password_hash, display_name, all_sites)
                VALUES (?, '{noop}test-password-123', ?, ?) RETURNING id
                """).params(username, username, siteIds.length == 0 && "GROUP_ADMIN".equals(role))
                .query(Long.class).single();
        jdbc.sql("INSERT INTO user_role (user_id, role_code) VALUES (?, ?)").params(id, role).update();
        for (Long siteId : siteIds) {
            jdbc.sql("INSERT INTO user_scenic_scope (user_id, site_id) VALUES (?, ?)").params(id, siteId).update();
        }
        return id;
    }
}
