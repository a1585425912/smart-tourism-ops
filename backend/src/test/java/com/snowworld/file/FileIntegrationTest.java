package com.snowworld.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.snowworld.IntegrationTestBase;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@AutoConfigureMockMvc
@TestPropertySource(properties = "snow.storage-dir=target/test-storage")
class FileIntegrationTest extends IntegrationTestBase {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R'};

    @Autowired
    MockMvc mvc;

    long siteA;
    long siteB;
    long templateId;

    @BeforeEach
    void setUp() {
        siteA = createSite("A", "景区A");
        siteB = createSite("B", "景区B");
        createUser("admin_a", "SITE_ADMIN", siteA);
        createUser("admin_b", "SITE_ADMIN", siteB);
        createUser("insp_ab", "INSPECTOR", siteA, siteB);
        createUser("insp_a2", "INSPECTOR", siteA);
        createUser("viewer_a", "VIEWER", siteA);
        templateId = jdbc.sql("""
                INSERT INTO inspection_template (name, category, items) VALUES ('测试模板', 'SERVICE',
                  '[{"code": "TOILET", "name": "卫生间", "required": true, "hint": "清洁"}]'::jsonb) RETURNING id
                """).query(Long.class).single();
    }

    MockHttpSession login(String username) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/v1/auth/login").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"test-password-123\"}"))
                .andExpect(status().isOk());
        return session;
    }

    JsonNode upload(MockHttpSession session, long siteId, String name, byte[] content, int expected) throws Exception {
        MvcResult result = mvc.perform(multipart("/api/v1/files")
                        .file(new MockMultipartFile("file", name, "image/png", content))
                        .param("siteId", String.valueOf(siteId)).param("bizType", "INSPECTION_RESULT")
                        .session(session).with(csrf()))
                .andExpect(status().is(expected)).andReturn();
        return JSON.readTree(result.getResponse().getContentAsByteArray());
    }

    JsonNode postJson(MockHttpSession session, String url, String body, int expected) throws Exception {
        MvcResult result = mvc.perform(post(url).session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected)).andReturn();
        return JSON.readTree(result.getResponse().getContentAsByteArray());
    }

    @Test
    void pngIsAcceptedStoredAndServedWithSafeHeaders() throws Exception {
        MockHttpSession insp = login("insp_ab");
        JsonNode ref = upload(insp, siteA, "..\\..\\evil/照片.png", PNG, 201);
        long id = ref.get("id").asLong();
        assertThat(ref.get("name").asString()).isEqualTo("照片.png");
        assertThat(ref.get("contentType").asString()).isEqualTo("image/png");
        assertThat(ref.get("url").asString()).isEqualTo("/api/v1/files/" + id);
        String key = jdbc.sql("SELECT object_key FROM file_asset WHERE id = ?").param(id).query(String.class).single();
        assertThat(key).matches("\\d{4}/\\d{2}/[0-9a-f-]{36}\\.png");

        mvc.perform(get("/api/v1/files/" + id).session(insp))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("inline")));
        // 其它景区的用户不能读取
        mvc.perform(get("/api/v1/files/" + id).session(login("admin_b")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SITE_FORBIDDEN"));
        mvc.perform(get("/api/v1/files/999999").session(insp)).andExpect(status().isNotFound());
    }

    @Test
    void disguisedTextFileAndForbiddenUploadsAreRejected() throws Exception {
        MockHttpSession insp = login("insp_ab");
        JsonNode error = upload(insp, siteA, "fake.png", "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8), 400);
        assertThat(error.get("code").asString()).isEqualTo("BAD_REQUEST");
        // 无权景区
        upload(login("insp_a2"), siteB, "a.png", PNG, 403);
        // 只读角色不能上传
        upload(login("viewer_a"), siteA, "a.png", PNG, 403);
        assertThat(jdbc.sql("SELECT count(*) FROM file_asset").query(Long.class).single()).isZero();
        // bizType 白名单
        mvc.perform(multipart("/api/v1/files").file(new MockMultipartFile("file", "a.png", "image/png", PNG))
                        .param("siteId", String.valueOf(siteA)).param("bizType", "OTHER").session(insp).with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void attachRejectsCrossSiteAndOtherUsersFiles() throws Exception {
        MockHttpSession admin = login("admin_a");
        MockHttpSession insp = login("insp_ab");
        long inspId = jdbc.sql("SELECT id FROM app_user WHERE username = 'insp_ab'").query(Long.class).single();
        JsonNode task = postJson(admin, "/api/v1/inspection-tasks", "{\"siteId\":" + siteA + ",\"templateId\":" + templateId
                + ",\"title\":\"附件校验\",\"assigneeId\":" + inspId + "}", 201);
        long taskId = task.get("id").asLong();
        postJson(admin, "/api/v1/inspection-tasks/" + taskId + "/transitions", "{\"action\":\"ASSIGN\",\"version\":0}", 200);
        task = postJson(insp, "/api/v1/inspection-tasks/" + taskId + "/transitions", "{\"action\":\"START\",\"version\":1}", 200);
        int version = task.get("version").asInt();

        long fileOtherSite = upload(insp, siteB, "b.png", PNG, 201).get("id").asLong();
        long fileOtherUser = upload(login("insp_a2"), siteA, "c.png", PNG, 201).get("id").asLong();
        long fileOk = upload(insp, siteA, "ok.png", PNG, 201).get("id").asLong();

        String url = "/api/v1/inspection-tasks/" + taskId + "/results";
        String tpl = "{\"version\":%d,\"items\":[{\"itemCode\":\"TOILET\",\"result\":\"OK\",\"fileIds\":[%d]}]}";
        postJson(insp, url, tpl.formatted(version, fileOtherSite), 400);
        postJson(insp, url, tpl.formatted(version, fileOtherUser), 400);
        // 失败的保存整体回滚, 版本号不变
        JsonNode saved = postJson(insp, url, tpl.formatted(version, fileOk), 200);
        assertThat(saved.get("results").get(0).get("files").get(0).get("id").asLong()).isEqualTo(fileOk);
        assertThat(jdbc.sql("SELECT biz_id FROM file_asset WHERE id = ?").param(fileOk).query(Long.class).single())
                .isEqualTo(taskId);
    }
}
