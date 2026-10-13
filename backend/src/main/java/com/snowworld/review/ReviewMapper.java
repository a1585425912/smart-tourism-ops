package com.snowworld.review;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.Data;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import tools.jackson.databind.JsonNode;

@Mapper
public interface ReviewMapper {

    @Data
    class ReviewRow {
        private Long id;
        private Long siteId;
        private String siteName;
        private Long sourceId;
        private String sourceCode;
        private String sourceRecordId;
        private String platform;
        private String authorMasked;
        private BigDecimal rating;
        private String contentMasked;
        private OffsetDateTime publishedAt;
        private OffsetDateTime importedAt;
        private String importBatch;
        private Boolean isComplaint;
        private String dataMode;
        private String analysisStatus;
        // 当前分析版本(可能为空)
        private Integer anVersion;
        private String anOrigin;
        private String anSentiment;
        private String[] anTopics;
        private String anSummary;
        private JsonNode anEvidence;
        private Boolean anNeedsHuman;
        private String anModel;
        private String anPromptVersion;
        private String anCreatedByName;
        private String anNote;
        private OffsetDateTime anCreatedAt;
    }

    @Data
    class AnalysisRow {
        private Long id;
        private Long reviewId;
        private Integer version;
        private String origin;
        private String sentiment;
        private String[] topics;
        private String summary;
        private JsonNode evidenceSpans;
        private Boolean needsHumanReview;
        private String model;
        private String promptVersion;
        private Long agentRunId;
        private Boolean isCurrent;
        private Long createdBy;
        private String createdByName;
        private String note;
        private OffsetDateTime createdAt;
    }

    @Data
    class NewReview {
        private Long id;
        private Long sourceId;
        private String sourceRecordId;
        private Long siteId;
        private String platform;
        private String authorMasked;
        private BigDecimal rating;
        private String content;
        private String contentMasked;
        private OffsetDateTime publishedAt;
        private String importBatch;
        private boolean complaint;
        private String dataMode;
    }

    @Data
    class Filter {
        private List<Long> siteIds;
        private String sentiment;
        private String topic;
        private String keyword;
        private String status;
        private Boolean complaint;
        private OffsetDateTime start;
        private OffsetDateTime end;
        private int limit;
        private int offset;
    }

    String SELECT = """
            SELECT r.id, r.site_id, s.name AS site_name, r.source_id, src.code AS source_code, r.source_record_id, r.platform,
                r.author_masked, r.rating, r.content_masked, r.published_at, r.imported_at, r.import_batch, r.is_complaint,
                r.data_mode, r.analysis_status,
                a.version AS an_version, a.origin AS an_origin, a.sentiment AS an_sentiment, a.topics AS an_topics,
                a.summary AS an_summary, a.evidence_spans AS an_evidence, a.needs_human_review AS an_needs_human,
                a.model AS an_model, a.prompt_version AS an_prompt_version, u.display_name AS an_created_by_name,
                a.note AS an_note, a.created_at AS an_created_at
            FROM review r
            JOIN scenic_site s ON s.id = r.site_id
            JOIN integration_source src ON src.id = r.source_id
            LEFT JOIN review_analysis a ON a.review_id = r.id AND a.is_current
            LEFT JOIN app_user u ON u.id = a.created_by
            """;

    String WHERE = """
            <where>
              r.site_id IN <foreach collection="f.siteIds" item="s" open="(" separator="," close=")">#{s}</foreach>
              <if test="f.sentiment != null and f.sentiment == 'PENDING'"> AND a.id IS NULL</if>
              <if test="f.sentiment != null and f.sentiment != 'PENDING'"> AND a.sentiment = #{f.sentiment}</if>
              <if test="f.topic != null"> AND #{f.topic} = ANY(a.topics)</if>
              <if test="f.keyword != null"> AND r.content_masked ILIKE '%' || #{f.keyword} || '%'</if>
              <if test="f.status != null"> AND r.analysis_status = #{f.status}</if>
              <if test="f.complaint != null"> AND r.is_complaint = #{f.complaint}</if>
              <if test="f.start != null"> AND r.published_at &gt;= #{f.start}</if>
              <if test="f.end != null"> AND r.published_at &lt; #{f.end}</if>
            </where>
            """;

    @Select("<script>" + SELECT + WHERE + " ORDER BY r.published_at DESC, r.id DESC LIMIT #{f.limit} OFFSET #{f.offset}</script>")
    List<ReviewRow> list(@Param("f") Filter filter);

    @Select("<script>SELECT count(*) FROM review r LEFT JOIN review_analysis a ON a.review_id = r.id AND a.is_current " + WHERE + "</script>")
    long count(@Param("f") Filter filter);

    @Select(SELECT + " WHERE r.id = #{id}")
    ReviewRow findById(long id);

    @Select("""
            <script>
            """ + SELECT + """
            WHERE r.id IN <foreach collection="ids" item="i" open="(" separator="," close=")">#{i}</foreach>
            ORDER BY r.id
            </script>
            """)
    List<ReviewRow> findByIds(@Param("ids") List<Long> ids);

    @Select("""
            SELECT a.*, u.display_name AS created_by_name FROM review_analysis a
            LEFT JOIN app_user u ON u.id = a.created_by
            WHERE a.review_id = #{reviewId} ORDER BY a.version DESC
            """)
    List<AnalysisRow> history(long reviewId);

    @Select("SELECT content_masked FROM review WHERE id = #{id}")
    String maskedContent(long id);

    @Insert("""
            INSERT INTO review (source_id, source_record_id, site_id, platform, author_masked, rating, content, content_masked,
                published_at, import_batch, is_complaint, data_mode)
            VALUES (#{sourceId}, #{sourceRecordId}, #{siteId}, #{platform}, #{authorMasked}, #{rating}, #{content}, #{contentMasked},
                #{publishedAt}, #{importBatch}, #{complaint}, #{dataMode})
            ON CONFLICT (source_id, source_record_id) DO NOTHING
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(NewReview review);

    /** 锁定评价行, 串行化同一评价的分析写入。 */
    @Select("SELECT analysis_status FROM review WHERE id = #{id} FOR UPDATE")
    String lockStatus(long id);

    @Select("SELECT coalesce(max(version), 0) FROM review_analysis WHERE review_id = #{reviewId}")
    int maxVersion(long reviewId);

    @Select("SELECT version FROM review_analysis WHERE review_id = #{reviewId} AND is_current")
    Integer currentVersion(long reviewId);

    @Update("UPDATE review_analysis SET is_current = FALSE WHERE review_id = #{reviewId} AND is_current")
    int clearCurrent(long reviewId);

    @Insert("""
            INSERT INTO review_analysis (review_id, version, origin, sentiment, topics, summary, evidence_spans, needs_human_review,
                model, prompt_version, agent_run_id, is_current, created_by, note)
            VALUES (#{reviewId}, #{version}, #{origin}, #{sentiment}, #{topics}, #{summary}, #{evidenceSpans}, #{needsHumanReview},
                #{model}, #{promptVersion}, #{agentRunId}, TRUE, #{createdBy}, #{note})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    void insertAnalysis(AnalysisRow row);

    @Update("UPDATE review SET analysis_status = #{status} WHERE id = #{id}")
    int updateStatus(@Param("id") long id, @Param("status") String status);

    /** 待分析评价(不含人工修正过的)。 */
    @Select("""
            <script>
            SELECT id FROM review
            WHERE site_id IN <foreach collection="siteIds" item="s" open="(" separator="," close=")">#{s}</foreach>
              AND analysis_status IN ('PENDING', 'FAILED')
            ORDER BY published_at DESC, id DESC LIMIT #{limit}
            </script>
            """)
    List<Long> pendingIds(@Param("siteIds") List<Long> siteIds, @Param("limit") int limit);

    // ---------- 汇总 ----------

    @Data
    class Totals {
        private Long total;
        private BigDecimal avgRating;
        private Long complaints;
        private OffsetDateTime latest;
    }

    @Data
    class KeyCount {
        private String key;
        private Long cnt;
        private Long negative;
    }

    @Data
    class DayCount {
        private LocalDate day;
        private Long total;
        private Long negative;
    }

    @Data
    class Sample {
        private Long reviewId;
        private String topic;
        private String text;
    }

    String RANGE = """
            r.site_id IN <foreach collection="siteIds" item="s" open="(" separator="," close=")">#{s}</foreach>
            AND r.published_at &gt;= #{start} AND r.published_at &lt; #{end}
            """;

    @Select("<script>SELECT count(*) AS total, round(avg(r.rating), 2) AS avg_rating, count(*) FILTER (WHERE r.is_complaint) AS complaints, max(r.published_at) AS latest FROM review r WHERE " + RANGE + "</script>")
    Totals totals(@Param("siteIds") List<Long> siteIds, @Param("start") OffsetDateTime start, @Param("end") OffsetDateTime end);

    @Select("<script>SELECT coalesce(a.sentiment, 'UNANALYZED') AS key, count(*) AS cnt FROM review r LEFT JOIN review_analysis a ON a.review_id = r.id AND a.is_current WHERE " + RANGE + " GROUP BY 1 ORDER BY 2 DESC</script>")
    List<KeyCount> bySentiment(@Param("siteIds") List<Long> siteIds, @Param("start") OffsetDateTime start, @Param("end") OffsetDateTime end);

    @Select("<script>SELECT t AS key, count(*) AS cnt, count(*) FILTER (WHERE a.sentiment = 'NEGATIVE') AS negative FROM review r JOIN review_analysis a ON a.review_id = r.id AND a.is_current, unnest(a.topics) t WHERE " + RANGE + " GROUP BY t ORDER BY 2 DESC</script>")
    List<KeyCount> byTopic(@Param("siteIds") List<Long> siteIds, @Param("start") OffsetDateTime start, @Param("end") OffsetDateTime end);

    @Select("<script>SELECT r.platform AS key, count(*) AS cnt FROM review r WHERE " + RANGE + " GROUP BY 1 ORDER BY 2 DESC</script>")
    List<KeyCount> byPlatform(@Param("siteIds") List<Long> siteIds, @Param("start") OffsetDateTime start, @Param("end") OffsetDateTime end);

    @Select("<script>SELECT (r.published_at AT TIME ZONE 'Asia/Shanghai')::date AS day, count(*) AS total, count(*) FILTER (WHERE a.sentiment = 'NEGATIVE') AS negative FROM review r LEFT JOIN review_analysis a ON a.review_id = r.id AND a.is_current WHERE " + RANGE + " GROUP BY 1 ORDER BY 1</script>")
    List<DayCount> daily(@Param("siteIds") List<Long> siteIds, @Param("start") OffsetDateTime start, @Param("end") OffsetDateTime end);

    /** 负面评价中指定主题的最新样本。 */
    @Select("<script>SELECT r.id AS review_id, #{topic} AS topic, r.content_masked AS text FROM review r JOIN review_analysis a ON a.review_id = r.id AND a.is_current WHERE " + RANGE + " AND a.sentiment = 'NEGATIVE' AND #{topic} = ANY(a.topics) ORDER BY r.published_at DESC LIMIT #{limit}</script>")
    List<Sample> negativeSamples(@Param("siteIds") List<Long> siteIds, @Param("start") OffsetDateTime start,
                                 @Param("end") OffsetDateTime end, @Param("topic") String topic, @Param("limit") int limit);

    @Select("<script>SELECT DISTINCT src.code FROM review r JOIN integration_source src ON src.id = r.source_id WHERE " + RANGE + " ORDER BY 1</script>")
    List<String> sourceCodes(@Param("siteIds") List<Long> siteIds, @Param("start") OffsetDateTime start, @Param("end") OffsetDateTime end);

    @Select("<script>SELECT DISTINCT r.data_mode FROM review r WHERE " + RANGE + "</script>")
    List<String> dataModes(@Param("siteIds") List<Long> siteIds, @Param("start") OffsetDateTime start, @Param("end") OffsetDateTime end);
}
