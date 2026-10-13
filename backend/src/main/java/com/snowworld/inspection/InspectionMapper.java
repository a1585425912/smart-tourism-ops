package com.snowworld.inspection;

import java.time.OffsetDateTime;
import java.util.List;
import lombok.Data;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import tools.jackson.databind.JsonNode;

@Mapper
public interface InspectionMapper {

    @Data
    class TemplateRow {
        private Long id;
        private String name;
        private String category;
        private JsonNode items;
        private Boolean active;
    }

    @Data
    class TaskRow {
        private Long id;
        private String taskNo;
        private Long siteId;
        private String siteName;
        private Long zoneId;
        private String zoneName;
        private Long templateId;
        private String templateName;
        private String title;
        private String description;
        private String priority;
        private Long assigneeId;
        private String assigneeName;
        private Long reviewerId;
        private String reviewerName;
        private OffsetDateTime dueAt;
        private String status;
        private String origin;
        private Long agentRunId;
        private JsonNode basisRefs;
        private String idempotencyKey;
        private Integer version;
        private Long createdBy;
        private OffsetDateTime createdAt;
        private OffsetDateTime updatedAt;
        private OffsetDateTime submittedAt;
        private OffsetDateTime completedAt;
        private Integer issueCount;
        private Integer openIssueCount;
    }

    @Data
    class ResultRow {
        private Long id;
        private Long taskId;
        private String itemCode;
        private String itemName;
        private String result;
        private String remark;
        private Long[] fileIds;
    }

    @Data
    class IssueRow {
        private Long id;
        private Long taskId;
        private String itemCode;
        private String description;
        private String severity;
        private String status;
        private Long rectifierId;
        private String rectifierName;
        private String rectifyNote;
        private Long[] rectifyFiles;
        private String reviewNote;
        private Long[] fileIds;
        private OffsetDateTime createdAt;
        private OffsetDateTime updatedAt;
    }

    @Data
    class LogRow {
        private Long id;
        private Long taskId;
        private String action;
        private String fromStatus;
        private String toStatus;
        private Long actorId;
        private String actorName;
        private String note;
        private OffsetDateTime createdAt;
    }

    @Data
    class StatusCount {
        private String status;
        private Integer cnt;
    }

    // ---------- 模板 ----------

    @Select("SELECT id, name, category, items, active FROM inspection_template WHERE active ORDER BY id")
    List<TemplateRow> findTemplates();

    @Select("SELECT id, name, category, items, active FROM inspection_template WHERE id = #{id}")
    TemplateRow findTemplate(long id);

    // ---------- 任务 ----------

    /** 注意: 该片段会被 script 引用, 不能出现 XML 特殊字符。 */
    String TASK_SELECT = """
            SELECT t.*, s.name AS site_name, z.name AS zone_name, tp.name AS template_name,
                   a.display_name AS assignee_name, r.display_name AS reviewer_name,
                   (SELECT count(*) FROM inspection_issue i WHERE i.task_id = t.id) AS issue_count,
                   (SELECT count(*) FROM inspection_issue i WHERE i.task_id = t.id AND i.status != 'CLOSED') AS open_issue_count
            FROM inspection_task t
            JOIN scenic_site s ON s.id = t.site_id
            JOIN inspection_template tp ON tp.id = t.template_id
            LEFT JOIN scenic_zone z ON z.id = t.zone_id
            LEFT JOIN app_user a ON a.id = t.assignee_id
            LEFT JOIN app_user r ON r.id = t.reviewer_id
            """;

    String TASK_FILTER = """
            WHERE t.site_id IN <foreach collection="siteIds" item="s" open="(" separator="," close=")">#{s}</foreach>
            <if test="statuses != null and statuses.size() > 0">
              AND t.status IN <foreach collection="statuses" item="st" open="(" separator="," close=")">#{st}</foreach>
            </if>
            <if test="assigneeId != null"> AND t.assignee_id = #{assigneeId}</if>
            <if test="keyword != null and keyword != ''">
              AND (t.title ILIKE '%' || #{keyword} || '%' OR t.task_no ILIKE '%' || #{keyword} || '%')
            </if>
            """;

    @Select("<script>" + TASK_SELECT + TASK_FILTER
            + " ORDER BY t.updated_at DESC, t.id DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<TaskRow> findTasks(@Param("siteIds") List<Long> siteIds, @Param("statuses") List<String> statuses,
                            @Param("assigneeId") Long assigneeId, @Param("keyword") String keyword,
                            @Param("size") int size, @Param("offset") int offset);

    @Select("<script>SELECT count(*) FROM inspection_task t " + TASK_FILTER + "</script>")
    long countTasks(@Param("siteIds") List<Long> siteIds, @Param("statuses") List<String> statuses,
                    @Param("assigneeId") Long assigneeId, @Param("keyword") String keyword);

    @Select(TASK_SELECT + " WHERE t.id = #{id}")
    TaskRow findTask(long id);

    @Select("SELECT id FROM inspection_task WHERE idempotency_key = #{key}")
    Long findIdByIdempotencyKey(String key);

    @Select("""
            SELECT coalesce(max(substring(task_no FROM 12)::int), 0) + 1 FROM inspection_task
            WHERE task_no LIKE #{prefix} || '%'
            """)
    int nextSeq(String prefix);

    @Insert("""
            INSERT INTO inspection_task (task_no, site_id, zone_id, template_id, title, description, priority, assignee_id,
                reviewer_id, due_at, status, origin, agent_run_id, basis_refs, idempotency_key, created_by, created_at, updated_at)
            VALUES (#{taskNo}, #{siteId}, #{zoneId}, #{templateId}, #{title}, #{description}, #{priority}, #{assigneeId},
                #{reviewerId}, #{dueAt}, #{status}, #{origin}, #{agentRunId}, #{basisRefs}, #{idempotencyKey}, #{createdBy},
                #{createdAt}, #{createdAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    void insertTask(TaskRow row);

    /** 带乐观锁的状态迁移, 返回 0 表示版本冲突。 */
    @Update("""
            UPDATE inspection_task SET status = #{to},
                assignee_id = coalesce(#{assigneeId}, assignee_id),
                reviewer_id = coalesce(#{reviewerId}, reviewer_id),
                submitted_at = CASE WHEN #{to} = 'SUBMITTED' THEN #{now} ELSE submitted_at END,
                completed_at = CASE WHEN #{to} = 'COMPLETED' THEN #{now} ELSE completed_at END,
                version = version + 1, updated_at = #{now}
            WHERE id = #{id} AND version = #{version}
            """)
    int transition(@Param("id") long id, @Param("version") int version, @Param("to") String to,
                   @Param("assigneeId") Long assigneeId, @Param("reviewerId") Long reviewerId,
                   @Param("now") OffsetDateTime now);

    @Update("UPDATE inspection_task SET version = version + 1, updated_at = #{now} WHERE id = #{id} AND version = #{version}")
    int touch(@Param("id") long id, @Param("version") int version, @Param("now") OffsetDateTime now);

    // ---------- 检查项结果 ----------

    @Select("SELECT * FROM inspection_item_result WHERE task_id = #{taskId} ORDER BY id")
    List<ResultRow> findResults(long taskId);

    @Insert("""
            INSERT INTO inspection_item_result (task_id, item_code, item_name, result, remark, file_ids)
            VALUES (#{taskId}, #{itemCode}, #{itemName}, #{result}, #{remark}, #{fileIds})
            ON CONFLICT (task_id, item_code) DO UPDATE SET item_name = EXCLUDED.item_name, result = EXCLUDED.result,
                remark = EXCLUDED.remark, file_ids = EXCLUDED.file_ids
            """)
    void upsertResult(ResultRow row);

    // ---------- 异常与整改 ----------

    @Select("""
            SELECT i.*, u.display_name AS rectifier_name FROM inspection_issue i
            LEFT JOIN app_user u ON u.id = i.rectifier_id
            WHERE i.task_id = #{taskId} ORDER BY i.id
            """)
    List<IssueRow> findIssues(long taskId);

    @Insert("""
            INSERT INTO inspection_issue (task_id, item_code, description, severity, status, file_ids, created_at, updated_at)
            VALUES (#{taskId}, #{itemCode}, #{description}, #{severity}, 'OPEN', #{fileIds}, #{createdAt}, #{createdAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    void insertIssue(IssueRow row);

    @Delete("DELETE FROM inspection_issue WHERE task_id = #{taskId}")
    int deleteIssues(long taskId);

    @Update("UPDATE inspection_issue SET rectifier_id = #{rectifierId}, updated_at = #{now} WHERE task_id = #{taskId} AND status = 'OPEN'")
    int assignRectifier(@Param("taskId") long taskId, @Param("rectifierId") Long rectifierId, @Param("now") OffsetDateTime now);

    @Update("""
            UPDATE inspection_issue SET status = 'RECTIFIED', rectifier_id = #{rectifierId}, rectify_note = #{note},
                rectify_files = #{files}, updated_at = #{now}
            WHERE task_id = #{taskId} AND status = 'OPEN'
            """)
    int markRectified(@Param("taskId") long taskId, @Param("rectifierId") long rectifierId, @Param("note") String note,
                      @Param("files") Long[] files, @Param("now") OffsetDateTime now);

    @Update("""
            UPDATE inspection_issue SET status = 'CLOSED', review_note = coalesce(#{note}, review_note), updated_at = #{now}
            WHERE task_id = #{taskId} AND status = 'RECTIFIED'
            """)
    int closeRectified(@Param("taskId") long taskId, @Param("note") String note, @Param("now") OffsetDateTime now);

    @Update("""
            UPDATE inspection_issue SET status = 'OPEN', review_note = #{note}, updated_at = #{now}
            WHERE task_id = #{taskId} AND status = 'RECTIFIED'
            """)
    int reopen(@Param("taskId") long taskId, @Param("note") String note, @Param("now") OffsetDateTime now);

    // ---------- 日志 ----------

    @Insert("""
            INSERT INTO inspection_log (task_id, action, from_status, to_status, actor_id, note, created_at)
            VALUES (#{taskId}, #{action}, #{fromStatus}, #{toStatus}, #{actorId}, #{note}, #{createdAt})
            """)
    void insertLog(LogRow row);

    @Select("""
            SELECT l.*, u.display_name AS actor_name FROM inspection_log l
            LEFT JOIN app_user u ON u.id = l.actor_id
            WHERE l.task_id = #{taskId} ORDER BY l.created_at, l.id
            """)
    List<LogRow> findLogs(long taskId);

    // ---------- 汇总 ----------

    @Select("""
            <script>
            SELECT status, count(*) AS cnt FROM inspection_task
            WHERE site_id IN <foreach collection="siteIds" item="s" open="(" separator="," close=")">#{s}</foreach>
              AND created_at &gt;= #{start} AND created_at &lt; #{end}
            GROUP BY status
            </script>
            """)
    List<StatusCount> countByStatus(@Param("siteIds") List<Long> siteIds, @Param("start") OffsetDateTime start,
                                    @Param("end") OffsetDateTime end);

    /** 当前逾期: 未完成/未取消且截止时间早于 now。 */
    @Select("""
            <script>
            SELECT t.id, t.task_no, t.site_id, t.title, t.status, t.due_at, a.display_name AS assignee_name
            FROM inspection_task t LEFT JOIN app_user a ON a.id = t.assignee_id
            WHERE t.site_id IN <foreach collection="siteIds" item="s" open="(" separator="," close=")">#{s}</foreach>
              AND t.status NOT IN ('COMPLETED', 'CANCELLED') AND t.due_at &lt; #{now}
            ORDER BY t.due_at
            </script>
            """)
    List<TaskRow> findOverdue(@Param("siteIds") List<Long> siteIds, @Param("now") OffsetDateTime now);

    /** 未关闭(OPEN/RECTIFIED)的异常。 */
    @Select("""
            <script>
            SELECT i.*, t.task_no AS task_no, t.site_id AS site_id FROM inspection_issue i
            JOIN inspection_task t ON t.id = i.task_id
            WHERE t.site_id IN <foreach collection="siteIds" item="s" open="(" separator="," close=")">#{s}</foreach>
              AND i.status != 'CLOSED' AND t.status != 'CANCELLED'
            ORDER BY CASE i.severity WHEN 'HIGH' THEN 0 WHEN 'MEDIUM' THEN 1 ELSE 2 END, i.created_at DESC
            </script>
            """)
    List<OpenIssueRow> findOpenIssues(@Param("siteIds") List<Long> siteIds);

    @Data
    class OpenIssueRow {
        private Long id;
        private Long taskId;
        private String taskNo;
        private Long siteId;
        private String description;
        private String severity;
        private String status;
        private OffsetDateTime createdAt;
    }

    @Select("""
            <script>
            SELECT max(updated_at) FROM inspection_task
            WHERE site_id IN <foreach collection="siteIds" item="s" open="(" separator="," close=")">#{s}</foreach>
            </script>
            """)
    OffsetDateTime latestUpdate(@Param("siteIds") List<Long> siteIds);

    @Select("SELECT site_id FROM scenic_zone WHERE id = #{zoneId} AND active")
    Long zoneSite(long zoneId);
}
