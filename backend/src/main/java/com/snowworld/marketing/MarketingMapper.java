package com.snowworld.marketing;

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
public interface MarketingMapper {

    @Data
    class PlanRow {
        private Long id;
        private String title;
        private Long[] siteIds;
        private String targetSegment;
        private JsonNode content;
        private String status;
        private Long agentRunId;
        private Long createdBy;
        private String createdByName;
        private OffsetDateTime createdAt;
    }

    String SELECT = """
            SELECT p.id, p.title, p.site_ids, p.target_segment, p.content, p.status, p.agent_run_id, p.created_by,
                u.display_name AS created_by_name, p.created_at
            FROM marketing_plan p LEFT JOIN app_user u ON u.id = p.created_by
            """;

    /** 只返回涉及景区全部在授权范围内的方案。 */
    @Select(SELECT + " WHERE p.site_ids <@ #{allowed} ORDER BY p.id DESC LIMIT 200")
    List<PlanRow> list(@Param("allowed") Long[] allowed);

    @Select(SELECT + " WHERE p.id = #{id}")
    PlanRow findById(long id);

    @Insert("""
            INSERT INTO marketing_plan (title, site_ids, target_segment, content, agent_run_id, created_by)
            VALUES (#{title}, #{siteIds}, #{targetSegment}, #{content}, #{agentRunId}, #{createdBy})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    void insert(PlanRow row);

    @Update("UPDATE marketing_plan SET status = #{status} WHERE id = #{id}")
    int updateStatus(@Param("id") long id, @Param("status") String status);
}
