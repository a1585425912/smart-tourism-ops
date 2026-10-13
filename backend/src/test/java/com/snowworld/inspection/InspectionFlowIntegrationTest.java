package com.snowworld.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import com.snowworld.common.TimeRange;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import tools.jackson.databind.JsonNode;

class InspectionFlowIntegrationTest extends InspectionTestSupport {

    @Autowired
    InspectionQueryPort queryPort;

    long siteA;
    long siteB;
    long templateId;
    long inspector;
    long otherInspector;
    long reviewer;

    @BeforeEach
    void setUp() {
        siteA = createSite("A", "景区A");
        siteB = createSite("B", "景区B");
        templateId = createTemplate();
        createUser("admin_a", "SITE_ADMIN", siteA);
        createUser("admin_b", "SITE_ADMIN", siteB);
        inspector = createUser("insp1", "INSPECTOR", siteA);
        otherInspector = createUser("insp2", "INSPECTOR", siteA);
        reviewer = createUser("rev1", "REVIEWER", siteA);
    }

    private JsonNode createTask(MockHttpSession admin, String extra) throws Exception {
        return postJson(admin, "/api/v1/inspection-tasks", "{\"siteId\":" + siteA + ",\"templateId\":" + templateId
                + ",\"title\":\"主入口日常巡检\",\"priority\":\"HIGH\"" + (extra == null ? "" : "," + extra) + "}", 201);
    }

    private List<String> actions(JsonNode detail) {
        List<String> out = new ArrayList<>();
        detail.get("allowedActions").forEach(n -> out.add(n.asString()));
        return out;
    }

    /** 创建并推进到 IN_PROGRESS, 返回最新详情。 */
    private JsonNode startedTask(MockHttpSession admin, MockHttpSession insp) throws Exception {
        JsonNode task = createTask(admin, "\"reviewerId\":" + reviewer);
        long id = task.get("id").asLong();
        task = transition(admin, id, "ASSIGN", task.get("version").asInt(), "\"assigneeId\":" + inspector, 200);
        return transition(insp, id, "START", task.get("version").asInt(), null, 200);
    }

    @Test
    void happyPathWithRectificationLoopAndReject() throws Exception {
        MockHttpSession admin = login("admin_a");
        MockHttpSession insp = login("insp1");
        MockHttpSession rev = login("rev1");

        JsonNode task = createTask(admin, "\"reviewerId\":" + reviewer);
        long id = task.get("id").asLong();
        assertThat(task.get("status").asString()).isEqualTo("DRAFT");
        assertThat(task.get("taskNo").asString()).matches("XJ\\d{8}-0001");
        assertThat(task.get("items")).hasSize(3);
        assertThat(actions(task)).containsExactly("ASSIGN", "CANCEL");

        task = transition(admin, id, "ASSIGN", task.get("version").asInt(), "\"assigneeId\":" + inspector, 200);
        assertThat(task.get("status").asString()).isEqualTo("ASSIGNED");
        assertThat(actions(getJson(insp, "/api/v1/inspection-tasks/" + id, 200))).containsExactly("START");

        task = transition(insp, id, "START", task.get("version").asInt(), null, 200);
        task = postJson(insp, "/api/v1/inspection-tasks/" + id + "/results", "{\"version\":" + task.get("version").asInt()
                + ",\"items\":[{\"itemCode\":\"TOILET\",\"result\":\"ABNORMAL\",\"remark\":\"地面结冰\",\"fileIds\":[]},"
                + "{\"itemCode\":\"WARMING\",\"result\":\"OK\",\"fileIds\":[]}],"
                + "\"issues\":[{\"itemCode\":\"TOILET\",\"description\":\"卫生间地面结冰\",\"severity\":\"HIGH\",\"fileIds\":[]}]}", 200);
        assertThat(task.get("results")).hasSize(2);
        assertThat(task.get("openIssueCount").asInt()).isEqualTo(1);

        task = transition(insp, id, "SUBMIT", task.get("version").asInt(), null, 200);
        assertThat(task.get("status").asString()).isEqualTo("SUBMITTED");

        // 存在异常时不能直接确认完成
        JsonNode rejected = transition(rev, id, "CONFIRM", task.get("version").asInt(), null, 409);
        assertThat(rejected.get("code").asString()).isEqualTo("ILLEGAL_TRANSITION");
        assertThat(actions(getJson(rev, "/api/v1/inspection-tasks/" + id, 200))).containsExactly("REQUIRE_RECTIFY");

        task = transition(rev, id, "REQUIRE_RECTIFY", task.get("version").asInt(), "\"note\":\"今日内整改\"", 200);
        assertThat(task.get("status").asString()).isEqualTo("RECTIFYING");

        task = transition(insp, id, "RECTIFY_DONE", task.get("version").asInt(), "\"rectifyNote\":\"已除冰\"", 200);
        assertThat(task.get("status").asString()).isEqualTo("PENDING_REVIEW");
        assertThat(task.get("issues").get(0).get("status").asString()).isEqualTo("RECTIFIED");

        task = transition(rev, id, "REJECT", task.get("version").asInt(), "\"note\":\"仍有薄冰\"", 200);
        assertThat(task.get("status").asString()).isEqualTo("RECTIFYING");
        assertThat(task.get("issues").get(0).get("status").asString()).isEqualTo("OPEN");
        assertThat(task.get("issues").get(0).get("reviewNote").asString()).isEqualTo("仍有薄冰");

        task = transition(insp, id, "RECTIFY_DONE", task.get("version").asInt(), "\"rectifyNote\":\"已铺防滑垫\"", 200);
        task = transition(rev, id, "APPROVE", task.get("version").asInt(), "\"note\":\"通过\"", 200);
        assertThat(task.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(task.get("issues").get(0).get("status").asString()).isEqualTo("CLOSED");
        assertThat(task.get("openIssueCount").asInt()).isZero();
        assertThat(actions(task)).isEmpty();
        // CREATE + 8 次迁移 + 1 次保存结果
        assertThat(task.get("logs")).hasSize(10);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE action LIKE 'INSPECTION_%'").query(Long.class).single())
                .isGreaterThanOrEqualTo(9);
    }

    @Test
    void cleanTaskCanBeConfirmedDirectly() throws Exception {
        MockHttpSession admin = login("admin_a");
        MockHttpSession insp = login("insp1");
        JsonNode task = startedTask(admin, insp);
        long id = task.get("id").asLong();
        task = postJson(insp, "/api/v1/inspection-tasks/" + id + "/results",
                "{\"version\":" + task.get("version").asInt() + ",\"items\":[" + ALL_OK + "],\"issues\":[]}", 200);
        task = transition(insp, id, "SUBMIT", task.get("version").asInt(), null, 200);
        task = transition(login("rev1"), id, "CONFIRM", task.get("version").asInt(), null, 200);
        assertThat(task.get("status").asString()).isEqualTo("COMPLETED");
    }

    @Test
    void illegalTransitionAndStaleVersionAreRejected() throws Exception {
        MockHttpSession admin = login("admin_a");
        MockHttpSession insp = login("insp1");
        JsonNode task = createTask(admin, "\"assigneeId\":" + inspector);
        long id = task.get("id").asLong();

        JsonNode illegal = transition(insp, id, "START", task.get("version").asInt(), null, 409);
        assertThat(illegal.get("code").asString()).isEqualTo("ILLEGAL_TRANSITION");

        transition(admin, id, "ASSIGN", 0, null, 200);
        // 仍使用旧版本号
        JsonNode stale = transition(insp, id, "START", 0, null, 409);
        assertThat(stale.get("code").asString()).isEqualTo("CONFLICT");
        assertThat(transition(admin, id, "BOGUS", 1, null, 400).get("code").asString()).isEqualTo("BAD_REQUEST");
    }

    @Test
    void inspectorCannotSeeOrActOnOthersTasks() throws Exception {
        MockHttpSession admin = login("admin_a");
        MockHttpSession insp = login("insp1");
        MockHttpSession other = login("insp2");
        JsonNode task = startedTask(admin, insp);
        long id = task.get("id").asLong();

        assertThat(getJson(other, "/api/v1/inspection-tasks/" + id, 403).get("code").asString()).isEqualTo("FORBIDDEN");
        transition(other, id, "SUBMIT", task.get("version").asInt(), null, 403);
        postJson(other, "/api/v1/inspection-tasks/" + id + "/results",
                "{\"version\":" + task.get("version").asInt() + ",\"items\":[" + ALL_OK + "]}", 403);
        assertThat(getJson(other, "/api/v1/inspection-tasks", 200).get("total").asInt()).isZero();
        assertThat(getJson(insp, "/api/v1/inspection-tasks", 200).get("total").asInt()).isEqualTo(1);
        // 巡检员不能派单或创建任务; 执行人也不能越权做复核动作
        postJson(insp, "/api/v1/inspection-tasks",
                "{\"siteId\":" + siteA + ",\"templateId\":" + templateId + ",\"title\":\"x\"}", 403);
        transition(insp, id, "CANCEL", task.get("version").asInt(), null, 409);
    }

    @Test
    void operatorCanReadScopedTasksWithoutExecutionPrivileges() throws Exception {
        createUser("operator_a", "OPERATOR", siteA);
        MockHttpSession operator = login("operator_a");
        JsonNode task = startedTask(login("admin_a"), login("insp1"));
        long id = task.get("id").asLong();

        JsonNode list = getJson(operator, "/api/v1/inspection-tasks?siteId=" + siteA, 200);
        assertThat(list.get("total").asInt()).isEqualTo(1);
        assertThat(list.get("items").get(0).get("id").asLong()).isEqualTo(id);
        assertThat(actions(getJson(operator, "/api/v1/inspection-tasks/" + id, 200))).isEmpty();
        assertThat(getJson(operator, "/api/v1/inspection-tasks?mine=true", 200).get("total").asInt()).isZero();
        getJson(operator, "/api/v1/inspection-tasks?siteId=" + siteB, 403);
        postJson(operator, "/api/v1/inspection-tasks/" + id + "/results",
                "{\"version\":0,\"items\":[]}", 403);
        transition(operator, id, "SUBMIT", task.get("version").asInt(), null, 403);
    }

    @Test
    void crossSiteAccessIsForbidden() throws Exception {
        MockHttpSession admin = login("admin_a");
        MockHttpSession adminB = login("admin_b");
        JsonNode task = createTask(admin, null);
        long id = task.get("id").asLong();

        assertThat(getJson(adminB, "/api/v1/inspection-tasks/" + id, 403).get("code").asString())
                .isEqualTo("SITE_FORBIDDEN");
        transition(adminB, id, "CANCEL", 0, null, 403);
        getJson(adminB, "/api/v1/inspection-tasks?siteId=" + siteA, 403);
        getJson(adminB, "/api/v1/inspection/candidates?siteId=" + siteA, 403);
        getJson(adminB, "/api/v1/inspection/stats?siteId=" + siteA, 403);
        assertThat(getJson(adminB, "/api/v1/inspection-tasks", 200).get("total").asInt()).isZero();
        // 不能把其它景区的人员指派进来
        long outsider = createUser("insp_b", "INSPECTOR", siteB);
        transition(admin, id, "ASSIGN", 0, "\"assigneeId\":" + outsider, 400);
    }

    @Test
    void submitRequiresAllRequiredItems() throws Exception {
        MockHttpSession admin = login("admin_a");
        MockHttpSession insp = login("insp1");
        JsonNode task = startedTask(admin, insp);
        long id = task.get("id").asLong();
        task = postJson(insp, "/api/v1/inspection-tasks/" + id + "/results", "{\"version\":" + task.get("version").asInt()
                + ",\"items\":[{\"itemCode\":\"TOILET\",\"result\":\"OK\",\"fileIds\":[]}]}", 200);

        JsonNode error = transition(insp, id, "SUBMIT", task.get("version").asInt(), null, 400);
        assertThat(error.get("code").asString()).isEqualTo("VALIDATION_FAILED");
        assertThat(error.get("details").get("missingItems").get(0).asString()).isEqualTo("取暖点");
        // 未知检查项与非法结果被拒绝
        postJson(insp, "/api/v1/inspection-tasks/" + id + "/results", "{\"version\":" + task.get("version").asInt()
                + ",\"items\":[{\"itemCode\":\"NOPE\",\"result\":\"OK\"}]}", 400);
    }

    @Test
    void candidatesAndListFilters() throws Exception {
        MockHttpSession admin = login("admin_a");
        JsonNode inspectors = getJson(admin, "/api/v1/inspection/candidates?siteId=" + siteA + "&role=INSPECTOR", 200);
        assertThat(inspectors).hasSize(2);
        JsonNode reviewers = getJson(admin, "/api/v1/inspection/candidates?siteId=" + siteA + "&role=REVIEWER", 200);
        assertThat(reviewers).hasSize(2);

        createTask(admin, null);
        JsonNode second = createTask(admin, "\"dueAt\":\"2020-01-01T00:00:00+08:00\"");
        assertThat(second.get("taskNo").asString()).endsWith("-0002");
        assertThat(second.get("overdue").asBoolean()).isTrue();
        assertThat(getJson(admin, "/api/v1/inspection-tasks?status=DRAFT&keyword=主入口", 200).get("total").asInt()).isEqualTo(2);
        assertThat(getJson(admin, "/api/v1/inspection-tasks?status=COMPLETED", 200).get("total").asInt()).isZero();
        assertThat(getJson(admin, "/api/v1/inspection-templates", 200)).hasSize(1);

        JsonNode stats = getJson(admin, "/api/v1/inspection/stats", 200);
        assertThat(stats.get("total").asInt()).isEqualTo(2);
        assertThat(stats.get("overdue").asInt()).isEqualTo(1);
        assertThat(stats.get("byStatus").get("DRAFT").asInt()).isEqualTo(2);
    }

    @Test
    void agentDraftIsIdempotentAndStaysDraft() throws Exception {
        long admin = jdbc.sql("SELECT id FROM app_user WHERE username = 'admin_a'").query(Long.class).single();
        // agent_run_id 无外键约束, 这里只验证草稿创建逻辑
        InspectionQueryPort.DraftResult first = queryPort.createAgentDraft(admin, siteA, null, "高峰时段防滑巡检",
                "依据客流预警", List.of("review:12", "flow:site:" + siteA), 77L, "agent:77:1");
        InspectionQueryPort.DraftResult again = queryPort.createAgentDraft(admin, siteA, null, "高峰时段防滑巡检",
                "依据客流预警", List.of("review:12"), 77L, "agent:77:1");
        assertThat(first.created()).isTrue();
        assertThat(again.created()).isFalse();
        assertThat(again.taskId()).isEqualTo(first.taskId());
        assertThat(jdbc.sql("SELECT count(*) FROM inspection_task").query(Long.class).single()).isEqualTo(1);

        JsonNode detail = getJson(login("admin_a"), "/api/v1/inspection-tasks/" + first.taskId(), 200);
        assertThat(detail.get("status").asString()).isEqualTo("DRAFT");
        assertThat(detail.get("origin").asString()).isEqualTo("AGENT_DRAFT");
        assertThat(detail.get("assigneeId").isNull()).isTrue();
        assertThat(detail.get("basisRefs")).hasSize(2);

        // 越权景区直接拒绝
        org.junit.jupiter.api.Assertions.assertThrows(com.snowworld.common.ApiException.class,
                () -> queryPort.createAgentDraft(admin, siteB, null, "x", null, List.of(), 78L, "agent:78:1"));

        InspectionQueryPort.InspectionSummary summary = queryPort.summary(List.of(siteA),
                new TimeRange(OffsetDateTime.now().minusDays(1), OffsetDateTime.now().plusDays(1)));
        assertThat(summary.total()).isEqualTo(1);
        assertThat(summary.byStatus()).containsEntry("DRAFT", 1);
    }
}
