package com.snowworld.auth;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.snowworld.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class AuthScopeIntegrationTest extends IntegrationTestBase {

    @Autowired
    MockMvc mvc;

    long siteA;
    long siteB;

    @BeforeEach
    void setUp() {
        siteA = createSite("A", "景区A");
        siteB = createSite("B", "景区B");
        createUser("admin_a", "SITE_ADMIN", siteA);
    }

    MockHttpSession login(String username) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/v1/auth/login").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"test-password-123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sites.length()").value(1));
        return session;
    }

    @Test
    void unauthenticatedRequestsGet401Json() throws Exception {
        mvc.perform(get("/api/v1/scenic-sites"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void wrongPasswordIsRejected() throws Exception {
        mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin_a\",\"password\":\"nope-nope-nope\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"));
    }

    @Test
    void writeWithoutCsrfTokenIsRejected() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin_a\",\"password\":\"test-password-123\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    }

    @Test
    void siteAdminOnlySeesAuthorizedSite() throws Exception {
        MockHttpSession session = login("admin_a");
        mvc.perform(get("/api/v1/scenic-sites").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].code").value("A"))
                .andExpect(jsonPath("$[0].boundary.type").value("MultiPolygon"));
        mvc.perform(get("/api/v1/scenic-sites/" + siteB + "/zones").session(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SITE_FORBIDDEN"));
        mvc.perform(get("/api/v1/devices").param("siteId", String.valueOf(siteB)).session(session))
                .andExpect(status().isForbidden());
    }

    @Test
    void scopeRevocationTakesEffectImmediately() throws Exception {
        MockHttpSession session = login("admin_a");
        jdbc.sql("DELETE FROM user_scenic_scope").update();
        mvc.perform(get("/api/v1/scenic-sites/" + siteA).session(session))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidPolygonIsRejectedAndValidOneSaved() throws Exception {
        MockHttpSession session = login("admin_a");
        String bowtie = """
                {"name":"自相交","zoneType":"AREA","boundary":{"type":"Polygon","coordinates":
                [[[126.595,45.775],[126.605,45.785],[126.605,45.775],[126.595,45.785],[126.595,45.775]]]}}
                """;
        mvc.perform(post("/api/v1/scenic-sites/" + siteA + "/zones").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(bowtie))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVALID_GEOMETRY"));
        String square = """
                {"name":"入口广场","zoneType":"ENTRANCE","capacity":800,"boundary":{"type":"Polygon","coordinates":
                [[[126.595,45.775],[126.600,45.775],[126.600,45.780],[126.595,45.780],[126.595,45.775]]]}}
                """;
        mvc.perform(post("/api/v1/scenic-sites/" + siteA + "/zones").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(square))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.boundary.type").value("Polygon"))
                .andExpect(jsonPath("$.outsideSite").value(false))
                .andExpect(jsonPath("$.version").value(0));
    }
}
