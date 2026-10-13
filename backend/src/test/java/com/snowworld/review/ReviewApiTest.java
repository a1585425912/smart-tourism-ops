package com.snowworld.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.snowworld.agent.AgentTestBase;
import com.snowworld.agent.AgentTestSupport;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.JsonNode;

class ReviewApiTest extends AgentTestBase {

    private static final String HEADER = "source_record_id,site_code,platform,published_at,rating,content,author,is_complaint\n";

    private JsonNode importCsv(String csv, boolean bom) throws Exception {
        byte[] body = csv.getBytes(StandardCharsets.UTF_8);
        if (bom) {
            byte[] withBom = new byte[body.length + 3];
            withBom[0] = (byte) 0xEF;
            withBom[1] = (byte) 0xBB;
            withBom[2] = (byte) 0xBF;
            System.arraycopy(body, 0, withBom, 3, body.length);
            body = withBom;
        }
        String resp = mvc.perform(multipart("/api/v1/imports/reviews")
                        .file(new MockMultipartFile("file", "reviews.csv", "text/csv", body))
                        .with(as(operatorA)).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json(resp);
    }

    @Test
    void csvImportIsIdempotentMasksPiiAndReportsLineErrors() throws Exception {
        String csv = HEADER
                + "r1,BXDSJ,携程,2026-02-17 15:30,5,\"冰雕很震撼，灯光很美\",张三,\n"
                + "r2,BXDSJ,美团,2026-02-17T16:00:00+08:00,1,\"排队太久，有事请打13812345678\",李四,是\n"
                + "r3,TYD,携程,2026-02-17,4,太阳岛不错,,\n"
                + "r4,XXX,携程,2026-02-17,4,未知景区,,\n"
                + "r5,BXDSJ,携程,不是时间,4,时间错误,,\n"
                + "r6,BXDSJ,携程,2026-02-17,9,评分越界,,\n"
                + "\"r7\",BXDSJ,抖音,2026-02-17 10:00,,\"多行\n内容，带\"\"引号\"\"\",王五,false\n";
        JsonNode first = importCsv(csv, true);
        assertThat(first.get("total").asInt()).isEqualTo(7);
        assertThat(first.get("inserted").asInt()).isEqualTo(3);
        assertThat(first.get("errors").size()).isEqualTo(4);
        assertThat(first.get("errors").toString()).contains("\"line\":4").contains("无权导入").contains("未知景区编码")
                .contains("published_at").contains("rating");

        JsonNode again = importCsv(csv, false);
        assertThat(again.get("inserted").asInt()).isZero();
        assertThat(again.get("duplicates").asInt()).isEqualTo(3);

        String masked = jdbc.sql("SELECT content_masked FROM review WHERE source_record_id = 'r2'").query(String.class).single();
        assertThat(masked).contains("138****5678").doesNotContain("13812345678");
        assertThat(jdbc.sql("SELECT author_masked FROM review WHERE source_record_id = 'r1'").query(String.class).single()).isEqualTo("张**");
        assertThat(jdbc.sql("SELECT is_complaint FROM review WHERE source_record_id = 'r2'").query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT content FROM review WHERE source_record_id = 'r7'").query(String.class).single())
                .isEqualTo("多行\n内容，带\"引号\"");
        assertThat(jdbc.sql("SELECT data_mode FROM review WHERE source_record_id = 'r1'").query(String.class).single()).isEqualTo("REAL");

        mvc.perform(multipart("/api/v1/imports/reviews")
                        .file(new MockMultipartFile("file", "x.csv", "text/csv", "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8)))
                        .with(as(operatorA)).with(csrf()))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("IMPORT_FAILED"));
    }

    @Test
    void humanCorrectionIsVersionedAndNotOverwrittenByReanalysis() throws Exception {
        importCsv(HEADER + "h1,BXDSJ,携程,2026-02-17 15:30,2,排队太久了，冷死了,,\n", false);
        long id = jdbc.sql("SELECT id FROM review WHERE source_record_id = 'h1'").query(Long.class).single();

        long run1 = run(siteAdminA, "{\"scenario\":\"REVIEW_ANALYSIS\",\"reviewIds\":[" + id + "]}");
        assertThat(load(run1).getResult().get("analyzed").asInt()).isEqualTo(1);

        mvc.perform(get("/api/v1/reviews/" + id).with(as(operatorA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysis.origin").value("MODEL"))
                .andExpect(jsonPath("$.analysis.version").value(1))
                .andExpect(jsonPath("$.analysis.evidenceSpans[0].text").isNotEmpty());

        String body = "{\"sentiment\":\"MIXED\",\"topics\":[\"QUEUE\",\"COLD\"],\"note\":\"人工复核\",\"expectedVersion\":1}";
        mvc.perform(patch("/api/v1/reviews/" + id + "/classification").with(as(operatorA)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysis.origin").value("HUMAN"))
                .andExpect(jsonPath("$.analysis.version").value(2))
                .andExpect(jsonPath("$.analysisStatus").value("HUMAN"))
                .andExpect(jsonPath("$.history.length()").value(2));
        // 版本冲突
        mvc.perform(patch("/api/v1/reviews/" + id + "/classification").with(as(operatorA)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
        // 非法主题
        mvc.perform(patch("/api/v1/reviews/" + id + "/classification").with(as(operatorA)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sentiment\":\"MIXED\",\"topics\":[\"HACK\"],\"expectedVersion\":2}"))
                .andExpect(status().isBadRequest());

        long run2 = run(siteAdminA, "{\"scenario\":\"REVIEW_ANALYSIS\",\"reviewIds\":[" + id + "]}");
        assertThat(load(run2).getResult().get("skippedHuman").asInt()).isEqualTo(1);
        mvc.perform(get("/api/v1/reviews/" + id).with(as(operatorA)))
                .andExpect(jsonPath("$.analysis.origin").value("HUMAN"))
                .andExpect(jsonPath("$.analysis.sentiment").value("MIXED"));
    }

    @Test
    void invalidModelOutputIsSanitized() throws Exception {
        importCsv(HEADER + "m1,BXDSJ,携程,2026-02-17 15:30,2,厕所很脏,,\n", false);
        long id = jdbc.sql("SELECT id FROM review WHERE source_record_id = 'm1'").query(Long.class).single();
        llm.use(new AgentTestSupport.ScriptedLlm().thenText("""
                {"results":[{"reviewId":%d,"sentiment":"ANGRY","topics":["HYGIENE","NOT_A_TOPIC"],
                  "summary":"卫生差","evidenceSpans":[{"text":"根本不存在的原文","topic":"HYGIENE"},{"text":"厕所很脏","topic":"HYGIENE"}],
                  "confidence":0.9}]}
                """.formatted(id)));
        long runId = run(siteAdminA, "{\"scenario\":\"REVIEW_ANALYSIS\",\"reviewIds\":[" + id + "]}");
        assertThat(load(runId).getStatus()).isEqualTo("SUCCEEDED");
        JsonNode a = json(mvc.perform(get("/api/v1/reviews/" + id).with(as(operatorA))).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).get("analysis");
        assertThat(a.get("sentiment").asString()).isEqualTo("UNKNOWN");
        assertThat(a.get("topics").toString()).isEqualTo("[\"HYGIENE\"]");
        assertThat(a.get("evidenceSpans").size()).isEqualTo(1);
        assertThat(a.get("evidenceSpans").get(0).get("start").asInt()).isZero();
        assertThat(a.get("needsHumanReview").asBoolean()).isTrue();
    }

    @Test
    void listSummaryAndScope() throws Exception {
        importCsv(HEADER + "s1,BXDSJ,携程,2026-02-17 15:30,1,排队太久了，太挤,,1\n"
                + "s2,BXDSJ,美团,2026-02-17 16:30,5,冰雕很震撼,,\n", false);
        jdbc.sql("""
                INSERT INTO review (source_id, source_record_id, site_id, platform, content, content_masked, published_at, data_mode)
                SELECT id, 'other', ?, '携程', '太阳岛评价', '太阳岛评价', now(), 'DEMO' FROM integration_source LIMIT 1
                """).param(tyd).update();
        run(siteAdminA, "{\"scenario\":\"REVIEW_ANALYSIS\",\"limit\":10}");

        mvc.perform(get("/api/v1/reviews").with(as(operatorA)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2));
        mvc.perform(get("/api/v1/reviews").param("sentiment", "NEGATIVE").with(as(operatorA)))
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/v1/reviews").param("keyword", "冰雕").with(as(operatorA)))
                .andExpect(jsonPath("$.items[0].contentMasked").value("冰雕很震撼"));
        mvc.perform(get("/api/v1/reviews").param("siteId", String.valueOf(tyd)).with(as(operatorA)))
                .andExpect(status().isForbidden());
        long other = jdbc.sql("SELECT id FROM review WHERE source_record_id = 'other'").query(Long.class).single();
        mvc.perform(get("/api/v1/reviews/" + other).with(as(operatorA))).andExpect(status().isForbidden());

        mvc.perform(get("/api/v1/reviews/summary").param("start", "2026-02-01T00:00:00+08:00")
                        .param("end", "2026-03-01T00:00:00+08:00").with(as(operatorA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.complaintCount").value(1))
                .andExpect(jsonPath("$.bySentiment.NEGATIVE").value(1))
                .andExpect(jsonPath("$.hotIssues[0].topic").value("QUEUE"))
                .andExpect(jsonPath("$.meta.dataMode").value("REAL"));
        mvc.perform(get("/api/v1/reviews/topics").with(as(operatorA))).andExpect(jsonPath("$.length()").value(11));
    }

    @Test
    void piiMasker() {
        assertThat(PiiMasker.mask("电话13812345678，证件230102199001011234，邮箱abc.def@example.com"))
                .isEqualTo("电话138****5678，证件230*************34，邮箱a***@example.com");
        assertThat(PiiMasker.mask("订单号 2026021812345678901 不变")).contains("2026021812345678901");
        assertThat(PiiMasker.maskName("13812345678")).isEqualTo("1**");
        assertThat(CsvParser.parse("a,b\r\n\"x,1\",\"y\"\"z\"\r\n\r\n")).extracting(CsvParser.Row::fields)
                .containsExactly(List.of("a", "b"), List.of("x,1", "y\"z"));
    }

    @Test
    void demoSeedProducesLabelledReviews() throws Exception {
        createSite("ZYDJ", "中央大街");
        new ReviewDemoSeedStep().seed(seedContext());
        long total = jdbc.sql("SELECT count(*) FROM review WHERE data_mode = 'DEMO'").query(Long.class).single();
        assertThat(total).isEqualTo(1200);
        long analyzed = jdbc.sql("SELECT count(*) FROM review_analysis").query(Long.class).single();
        assertThat(analyzed).isBetween(950L, 1100L);
        assertThat(jdbc.sql("SELECT count(*) FROM review WHERE content_masked ~ '1[3-9][0-9]{9}'").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM review WHERE content LIKE '%忽略以上%' OR content LIKE 'SYSTEM:%'").query(Long.class).single())
                .isEqualTo(2);
        assertThat(new ReviewDemoSeedStep().alreadySeeded(seedContext())).isTrue();
    }
}
