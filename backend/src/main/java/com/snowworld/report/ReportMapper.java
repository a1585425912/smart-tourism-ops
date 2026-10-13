package com.snowworld.report;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.Data;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import tools.jackson.databind.JsonNode;

@Mapper
public interface ReportMapper {

    @Data
    class ReportRow {
        private Long id;
        private Long siteId;
        private String siteName;
        private LocalDate reportDate;
        private Integer version;
        private String title;
        private JsonNode snapshot;
        private JsonNode content;
        private OffsetDateTime dataAsOf;
        private String dataMode;
        private String provider;
        private String model;
        private String promptVersion;
        private Long agentRunId;
        private Long createdBy;
        private String createdByName;
        private OffsetDateTime createdAt;
    }

    String LIST_COLUMNS = """
            r.id, r.site_id, s.name AS site_name, r.report_date, r.version, r.title, r.data_as_of, r.data_mode, r.provider,
            r.model, r.prompt_version, r.agent_run_id, r.created_by, u.display_name AS created_by_name, r.created_at
            """;

    @Select("""
            <script>
            SELECT """ + LIST_COLUMNS + """
            FROM operation_report r JOIN scenic_site s ON s.id = r.site_id LEFT JOIN app_user u ON u.id = r.created_by
            WHERE r.site_id IN <foreach collection="siteIds" item="i" open="(" separator="," close=")">#{i}</foreach>
            ORDER BY r.report_date DESC, r.site_id, r.version DESC LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<ReportRow> list(@Param("siteIds") List<Long> siteIds, @Param("limit") int limit, @Param("offset") int offset);

    @Select("""
            <script>
            SELECT count(*) FROM operation_report r
            WHERE r.site_id IN <foreach collection="siteIds" item="i" open="(" separator="," close=")">#{i}</foreach>
            </script>
            """)
    long count(@Param("siteIds") List<Long> siteIds);

    @Select("SELECT " + LIST_COLUMNS + """
            , r.snapshot, r.content
            FROM operation_report r JOIN scenic_site s ON s.id = r.site_id LEFT JOIN app_user u ON u.id = r.created_by
            WHERE r.id = #{id}
            """)
    ReportRow findById(long id);

    /** 同一景区同一天的报告串行生成版本号。 */
    @Select("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtext('report:' || #{siteId} || ':' || #{date}))) t")
    Integer lock(@Param("siteId") long siteId, @Param("date") String date);

    @Select("SELECT coalesce(max(version), 0) FROM operation_report WHERE site_id = #{siteId} AND report_date = #{date}")
    int maxVersion(@Param("siteId") long siteId, @Param("date") LocalDate date);

    @Insert("""
            INSERT INTO operation_report (site_id, report_date, version, title, snapshot, content, data_as_of, data_mode,
                provider, model, prompt_version, agent_run_id, created_by)
            VALUES (#{siteId}, #{reportDate}, #{version}, #{title}, #{snapshot}, #{content}, #{dataAsOf}, #{dataMode},
                #{provider}, #{model}, #{promptVersion}, #{agentRunId}, #{createdBy})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    void insert(ReportRow row);
}
