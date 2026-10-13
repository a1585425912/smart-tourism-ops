package com.snowworld.agent;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.snowworld.IntegrationTestBase;
import com.snowworld.auth.AuthUser;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;

/** Agent 相关集成测试的公共夹具: 两个景区、不同角色用户、可替换的模型与业务查询替身。 */
@AutoConfigureMockMvc
@Import(AgentTestSupport.class)
@TestPropertySource(properties = {"snow.ai.retry-backoff-ms=5", "snow.ai.provider=fake"})
public abstract class AgentTestBase extends IntegrationTestBase {

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected AgentOrchestrator orchestrator;
    @Autowired
    protected AgentRunMapper runMapper;
    @Autowired
    protected AgentTestSupport.SwitchableLlm llm;
    @Autowired
    protected AgentTestSupport.StubFlow flow;
    @Autowired
    protected AgentTestSupport.StubInspection inspection;

    protected long bxdsj;
    protected long tyd;
    protected AuthUser operatorA;
    protected AuthUser siteAdminA;
    protected AuthUser groupAdmin;

    @BeforeEach
    void setUpAgentFixture() {
        llm.reset();
        flow.reset();
        inspection.reset();
        bxdsj = createSite("BXDSJ", "哈尔滨冰雪大世界");
        tyd = createSite("TYD", "太阳岛风景区");
        jdbc.sql("UPDATE scenic_site SET short_name = CASE code WHEN 'BXDSJ' THEN '冰雪大世界' ELSE '太阳岛' END").update();
        operatorA = authUser(createUser("operator_a", "OPERATOR", bxdsj), "operator_a", "OPERATOR");
        siteAdminA = authUser(createUser("admin_a", "SITE_ADMIN", bxdsj), "admin_a", "SITE_ADMIN");
        groupAdmin = authUser(createUser("group_admin", "GROUP_ADMIN"), "group_admin", "GROUP_ADMIN");
    }

    protected static AuthUser authUser(long id, String username, String role) {
        return new AuthUser(id, username, username, "", true, List.of(role));
    }

    protected static RequestPostProcessor as(AuthUser u) {
        return user(u);
    }

    /** 通过 API 创建任务并同步执行(测试中后台 worker 关闭)。 */
    protected long run(AuthUser u, String body) throws Exception {
        String resp = mvc.perform(post("/api/v1/agent/runs").with(as(u)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        long runId = AgentJson.MAPPER.readTree(resp).get("runId").asLong();
        orchestrator.execute(runId, () -> { });
        return runId;
    }

    protected long ask(AuthUser u, String question) throws Exception {
        return run(u, AgentJson.write(java.util.Map.of("scenario", "QA", "question", question)));
    }

    protected AgentRunMapper.RunRow load(long runId) {
        return runMapper.findById(runId);
    }

    protected List<AgentRunMapper.ToolCallRow> steps(long runId) {
        return runMapper.toolCalls(runId, 0);
    }

    protected static String answer(AgentRunMapper.RunRow run) {
        return run.getResult().path("answer").asString("");
    }

    protected static String limitations(AgentRunMapper.RunRow run) {
        return run.getResult().path("limitations").toString();
    }

    @Autowired
    protected com.snowworld.common.SnowProperties props;

    protected com.snowworld.demo.DemoSeedContext seedContext() {
        return new com.snowworld.demo.DemoSeedContext(jdbc, props, java.time.Clock.systemDefaultZone(), new java.util.Random(7),
                java.time.OffsetDateTime.now(), java.time.LocalDate.of(2024, 1, 1));
    }

    protected static JsonNode json(String s) {
        return AgentJson.MAPPER.readTree(s);
    }
}
