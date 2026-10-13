package com.snowworld.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import com.snowworld.common.SnowProperties;
import com.snowworld.demo.DemoSeedContext;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class InspectionDemoSeedStepTest extends InspectionTestSupport {

    @Autowired
    InspectionDemoSeedStep step;
    @Autowired
    SnowProperties props;
    @Autowired
    Clock clock;

    private DemoSeedContext context() {
        return new DemoSeedContext(jdbc, props, clock, new Random(42), OffsetDateTime.now(), LocalDate.now(clock).minusDays(29));
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    @Test
    void skipsQuietlyWhenPrerequisitesAreMissing() {
        step.seed(context());
        assertThat(step.alreadySeeded(context())).isFalse();
    }

    @Test
    void seedsConsistentTasksAcrossSites() throws Exception {
        long bx = createSite("BXDSJ", "冰雪大世界");
        long tyd = createSite("TYD", "太阳岛");
        createTemplate();
        createUser("admin", "GROUP_ADMIN");
        createUser("bxdsj_admin", "SITE_ADMIN", bx);
        createUser("inspector1", "INSPECTOR", bx, tyd);
        createUser("inspector2", "INSPECTOR", bx, tyd);
        createUser("reviewer", "REVIEWER", bx, tyd);
        jdbc.sql("""
                INSERT INTO scenic_zone (site_id, code, name, boundary)
                VALUES (?, 'Z1', '主入口', ST_MakeEnvelope(126.595, 45.775, 126.600, 45.780, 4326))
                """).param(bx).update();

        assertThat(step.alreadySeeded(context())).isFalse();
        step.seed(context());
        assertThat(step.alreadySeeded(context())).isTrue();

        // ZYDJ 不存在时只生成另外两个景区的任务
        assertThat(count("SELECT count(*) FROM inspection_task")).isEqualTo(31);
        assertThat(count("SELECT count(DISTINCT status) FROM inspection_task")).isGreaterThanOrEqualTo(7);
        assertThat(count("SELECT count(*) FROM inspection_task WHERE origin = 'AGENT_DRAFT' AND status <> 'DRAFT'")).isZero();
        step.seedScheduled(context());
        assertThat(count("SELECT count(*) FROM inspection_task WHERE origin='SCHEDULE'")).isEqualTo(60);
        assertThat(count("SELECT count(DISTINCT created_at::date) FROM inspection_task WHERE origin='SCHEDULE'")).isEqualTo(30);
        step.seedScheduled(context());
        assertThat(count("SELECT count(*) FROM inspection_task")).isEqualTo(91);
        assertThat(count("""
                SELECT count(*) FROM inspection_task t WHERE t.origin='SCHEDULE'
                AND t.assignee_id IS NOT NULL
                AND NOT EXISTS (SELECT 1 FROM user_scenic_scope s WHERE s.user_id=t.assignee_id AND s.site_id=t.site_id)
                """)).isZero();
        // 状态与日志、异常保持一致
        assertThat(count("""
                SELECT count(*) FROM inspection_task t WHERE t.status <> (
                    SELECT l.to_status FROM inspection_log l WHERE l.task_id = t.id ORDER BY l.created_at DESC, l.id DESC LIMIT 1)
                """)).isZero();
        assertThat(count("""
                SELECT count(*) FROM inspection_task t WHERE t.version <> (
                    SELECT count(*) - 1 FROM inspection_log l WHERE l.task_id = t.id)
                """)).isZero();
        assertThat(count("""
                SELECT count(*) FROM inspection_task t JOIN inspection_issue i ON i.task_id = t.id
                WHERE (t.status = 'COMPLETED' AND i.status <> 'CLOSED')
                   OR (t.status = 'RECTIFYING' AND i.status <> 'OPEN')
                   OR (t.status = 'PENDING_REVIEW' AND i.status <> 'RECTIFIED')
                """)).isZero();
        assertThat(count("""
                SELECT count(*) FROM inspection_task t WHERE t.status IN ('RECTIFYING', 'PENDING_REVIEW')
                  AND NOT EXISTS (SELECT 1 FROM inspection_issue i WHERE i.task_id = t.id)
                """)).isZero();
        assertThat(count("""
                SELECT count(*) FROM inspection_task
                WHERE status NOT IN ('DRAFT', 'CANCELLED') AND assignee_id IS NULL
                """)).isZero();
        // 所有时间不晚于业务当前时间, 且日志按时间递增
        assertThat(count("SELECT count(*) FROM inspection_log WHERE created_at > now() + interval '1 minute'")).isZero();
        assertThat(count("SELECT count(*) FROM inspection_task WHERE updated_at < created_at")).isZero();

        // 生成的数据可被正常接口读取与继续流转
        var session = login("bxdsj_admin");
        JsonNode page = getJson(session, "/api/v1/inspection-tasks?status=PENDING_REVIEW,SUBMITTED&size=50", 200);
        assertThat(page.get("total").asInt()).isPositive();
        JsonNode detail = getJson(session, "/api/v1/inspection-tasks/" + page.get("items").get(0).get("id").asLong(), 200);
        assertThat(detail.get("allowedActions").size()).isPositive();
        assertThat(detail.get("logs").size()).isGreaterThanOrEqualTo(4);
    }
}
