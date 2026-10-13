package com.snowworld.inspection;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.snowworld.IntegrationTestBase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 巡检与文件集成测试的公共脚手架。 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "snow.storage-dir=target/test-storage")
abstract class InspectionTestSupport extends IntegrationTestBase {

    protected static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    protected MockMvc mvc;

    protected MockHttpSession login(String username) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/v1/auth/login").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"test-password-123\"}"))
                .andExpect(status().isOk());
        return session;
    }

    protected JsonNode postJson(MockHttpSession session, String url, String body, int expectedStatus) throws Exception {
        MvcResult result = mvc.perform(post(url).session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expectedStatus)).andReturn();
        return JSON.readTree(result.getResponse().getContentAsByteArray());
    }

    protected JsonNode getJson(MockHttpSession session, String url, int expectedStatus) throws Exception {
        MvcResult result = mvc.perform(get(url).session(session))
                .andExpect(status().is(expectedStatus)).andReturn();
        return JSON.readTree(result.getResponse().getContentAsByteArray());
    }

    protected JsonNode transition(MockHttpSession session, long taskId, String action, int version, String extra,
                                  int expectedStatus) throws Exception {
        String body = "{\"action\":\"" + action + "\",\"version\":" + version + (extra == null ? "" : "," + extra) + "}";
        return postJson(session, "/api/v1/inspection-tasks/" + taskId + "/transitions", body, expectedStatus);
    }

    /** 两个必填项 + 一个选填项的模板。 */
    protected long createTemplate() {
        return jdbc.sql("""
                INSERT INTO inspection_template (name, category, items) VALUES ('测试模板', 'SERVICE', '[
                  {"code": "TOILET", "name": "卫生间", "required": true, "hint": "清洁"},
                  {"code": "WARMING", "name": "取暖点", "required": true, "hint": "温度"},
                  {"code": "TRASH", "name": "垃圾收集", "required": false, "hint": "未满溢"}
                ]'::jsonb) RETURNING id
                """).query(Long.class).single();
    }

    protected static final String ALL_OK = """
            {"itemCode":"TOILET","result":"OK","fileIds":[]},{"itemCode":"WARMING","result":"OK","fileIds":[]}
            """;
}
