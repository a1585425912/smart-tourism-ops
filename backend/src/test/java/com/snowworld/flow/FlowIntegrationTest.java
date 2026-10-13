package com.snowworld.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.snowworld.IntegrationTestBase;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class FlowIntegrationTest extends IntegrationTestBase {

    @Autowired
    MockMvc mvc;

    long siteA;
    long siteB;
    MockHttpSession session;

    // 使用过去的日期, 避免"晚于当前时间"校验
    static final String DAY = "2025-01-10";

    @BeforeEach
    void setUp() throws Exception {
        siteA = createSite("A", "景区A");
        siteB = createSite("B", "景区B");
        jdbc.sql("""
                INSERT INTO scenic_zone (site_id, code, name, boundary, capacity)
                VALUES (?, 'Z1', '区域1', ST_MakeEnvelope(126.595, 45.775, 126.600, 45.780, 4326), 1000)
                """).param(siteA).update();
        createUser("op_a", "SITE_ADMIN", siteA);
        session = new MockHttpSession();
        mvc.perform(post("/api/v1/auth/login").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"op_a\",\"password\":\"test-password-123\"}")).andExpect(status().isOk());
    }

    MockMultipartFile csv(String body) {
        return new MockMultipartFile("file", "flows.csv", "text/csv",
                ("source_record_id,biz_time,zone_code,metric,value\n" + body).getBytes(StandardCharsets.UTF_8));
    }

    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder summary(long site) {
        return get("/api/v1/flows/summary").param("siteId", String.valueOf(site))
                .param("start", DAY + "T00:00:00+08:00").param("end", "2025-01-11T00:00:00+08:00");
    }

    @Test
    void duplicateImportDoesNotDoubleCountAndLateDataUpdatesAggregates() throws Exception {
        String rows = """
                r1,%sT10:00:00+08:00,,ENTRY,100
                r2,%sT10:00:00+08:00,,IN_PARK,100
                r3,%sT11:00:00+08:00,,ENTRY,50
                r4,%sT10:30:00+08:00,Z1,ZONE_COUNT,40
                """.formatted(DAY, DAY, DAY, DAY);
        mvc.perform(multipart("/api/v1/imports/flows").file(csv(rows)).param("siteId", String.valueOf(siteA)).session(session).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inserted").value(4))
                .andExpect(jsonPath("$.duplicates").value(0));
        mvc.perform(multipart("/api/v1/imports/flows").file(csv(rows)).param("siteId", String.valueOf(siteA)).session(session).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inserted").value(0))
                .andExpect(jsonPath("$.duplicates").value(4));
        mvc.perform(summary(siteA).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries").value(150))
                .andExpect(jsonPath("$.inParkCurrent").value(100))
                .andExpect(jsonPath("$.meta.dataMode").value("REAL"));

        // 迟到数据: 10 点的桶追加一条, 小时汇总应被重算
        mvc.perform(multipart("/api/v1/imports/flows").file(csv("r5,%sT10:40:00+08:00,,ENTRY,30\n".formatted(DAY)))
                        .param("siteId", String.valueOf(siteA)).session(session).with(csrf()))
                .andExpect(jsonPath("$.inserted").value(1));
        assertThat(jdbc.sql("""
                SELECT sum_value FROM flow_aggregate WHERE site_id = ? AND zone_id = 0 AND metric = 'ENTRY' AND granularity = 'HOUR'
                  AND bucket_start = ?::timestamptz
                """).params(siteA, DAY + "T10:00:00+08:00").query(Double.class).single()).isEqualTo(130.0);
        assertThat(jdbc.sql("""
                SELECT sum_value FROM flow_aggregate WHERE site_id = ? AND zone_id = 0 AND metric = 'ENTRY' AND granularity = 'DAY'
                """).param(siteA).query(Double.class).single()).isEqualTo(180.0);
    }

    @Test
    void invalidRowsRejectWholeFileWithLineNumbers() throws Exception {
        String rows = """
                ok1,%sT10:00:00+08:00,,ENTRY,10
                bad1,not-a-time,,ENTRY,10
                bad2,%sT10:00:00+08:00,,ENTRY,-5
                bad3,%sT10:00:00+08:00,NOPE,ZONE_COUNT,5
                """.formatted(DAY, DAY, DAY);
        mvc.perform(multipart("/api/v1/imports/flows").file(csv(rows)).param("siteId", String.valueOf(siteA)).session(session).with(csrf()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IMPORT_FAILED"))
                .andExpect(jsonPath("$.details.errors.length()").value(3))
                .andExpect(jsonPath("$.details.errors[0].line").value(3));
        assertThat(jdbc.sql("SELECT count(*) FROM flow_event").query(Long.class).single()).isZero();
    }

    @Test
    void missingDataIsNullNotZeroAndCrossSiteIsForbidden() throws Exception {
        mvc.perform(summary(siteA).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries").isEmpty())
                .andExpect(jsonPath("$.inParkCurrent").isEmpty())
                .andExpect(jsonPath("$.loadLevel").value("UNKNOWN"))
                .andExpect(jsonPath("$.meta.quality").value("MISSING"));
        mvc.perform(summary(siteB).session(session)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/flows/zones?siteId=" + siteB).session(session)).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/v1/imports/flows").file(csv("x,%sT10:00:00+08:00,,ENTRY,1\n".formatted(DAY)))
                .param("siteId", String.valueOf(siteB)).session(session).with(csrf())).andExpect(status().isForbidden());
    }

    @Test
    void trendRangeIsLimited() throws Exception {
        OffsetDateTime start = OffsetDateTime.parse("2025-01-01T00:00:00+08:00");
        mvc.perform(get("/api/v1/flows/trend").session(session).param("siteId", String.valueOf(siteA))
                        .param("metric", "ENTRY").param("granularity", "HOUR")
                        .param("start", start.toString()).param("end", start.plusDays(40).toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RANGE_TOO_LARGE"));
    }
}
