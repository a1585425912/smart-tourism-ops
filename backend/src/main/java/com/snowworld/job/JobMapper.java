package com.snowworld.job;

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
public interface JobMapper {

    @Data
    class JobRow {
        private Long id;
        private String jobType;
        private String idempotencyKey;
        private Long refId;
        private JsonNode payload;
        private String status;
        private Integer attempts;
        private Integer maxAttempts;
        private String leaseOwner;
        private OffsetDateTime leaseUntil;
        private OffsetDateTime heartbeatAt;
        private Boolean cancelRequested;
        private OffsetDateTime runAfter;
        private String lastError;
        private Long createdBy;
        private OffsetDateTime createdAt;
        private OffsetDateTime updatedAt;
        private OffsetDateTime finishedAt;
    }

    /** 幂等入队: 相同 idempotency_key 已存在时不重复创建。 */
    @Insert("""
            INSERT INTO background_job (job_type, idempotency_key, ref_id, payload, max_attempts, created_by)
            VALUES (#{jobType}, #{idempotencyKey}, #{refId}, #{payload}, #{maxAttempts}, #{createdBy})
            ON CONFLICT (idempotency_key) DO NOTHING
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(JobRow row);

    @Select("SELECT * FROM background_job WHERE idempotency_key = #{key}")
    JobRow findByKey(String key);

    @Select("SELECT * FROM background_job WHERE id = #{id}")
    JobRow findById(long id);

    /**
     * 领取一个可执行任务: 排队中且到达执行时间, 或运行中但租约已过期(进程崩溃/重启后恢复)。
     * SKIP LOCKED 保证多实例下不会重复领取。
     */
    @Select("""
            UPDATE background_job SET status = 'RUNNING', lease_owner = #{owner}, lease_until = now() + make_interval(secs => #{leaseSeconds}),
                heartbeat_at = now(), attempts = attempts + 1, updated_at = now()
            WHERE id = (
                SELECT id FROM background_job
                WHERE ((status = 'QUEUED' AND run_after <= now()) OR (status = 'RUNNING' AND lease_until < now()))
                  AND cancel_requested = FALSE
                ORDER BY run_after, id
                FOR UPDATE SKIP LOCKED
                LIMIT 1)
            RETURNING *
            """)
    JobRow claim(@Param("owner") String owner, @Param("leaseSeconds") int leaseSeconds);

    @Update("UPDATE background_job SET lease_until = now() + make_interval(secs => #{leaseSeconds}), heartbeat_at = now() WHERE id = #{id} AND lease_owner = #{owner}")
    int heartbeat(@Param("id") long id, @Param("owner") String owner, @Param("leaseSeconds") int leaseSeconds);

    @Update("UPDATE background_job SET status = 'SUCCEEDED', lease_owner = NULL, lease_until = NULL, finished_at = now(), updated_at = now(), last_error = NULL WHERE id = #{id} AND lease_owner = #{owner}")
    int succeed(@Param("id") long id, @Param("owner") String owner);

    @Update("""
            UPDATE background_job SET status = #{status}, lease_owner = NULL, lease_until = NULL, last_error = #{error},
                run_after = now() + make_interval(secs => #{retryDelaySeconds}), updated_at = now(),
                finished_at = CASE WHEN #{status} IN ('FAILED', 'CANCELLED') THEN now() ELSE NULL END
            WHERE id = #{id} AND lease_owner = #{owner}
            """)
    int release(@Param("id") long id, @Param("owner") String owner, @Param("status") String status,
                @Param("error") String error, @Param("retryDelaySeconds") int retryDelaySeconds);

    @Update("""
            UPDATE background_job SET cancel_requested = TRUE, updated_at = now(),
                status = CASE WHEN status = 'QUEUED' THEN 'CANCELLED' ELSE status END,
                finished_at = CASE WHEN status = 'QUEUED' THEN now() ELSE finished_at END
            WHERE id = #{id} AND status IN ('QUEUED', 'RUNNING')
            """)
    int requestCancel(long id);

    @Select("SELECT cancel_requested FROM background_job WHERE id = #{id}")
    boolean isCancelRequested(long id);

    /** 运行中但租约过期又已达最大重试次数的任务直接判定失败, 避免无限恢复。 */
    @Update("""
            UPDATE background_job SET status = 'FAILED', last_error = coalesce(last_error, '') || ' [租约过期且重试次数用尽]',
                lease_owner = NULL, lease_until = NULL, finished_at = now(), updated_at = now()
            WHERE status = 'RUNNING' AND lease_until < now() AND attempts >= max_attempts
            """)
    int failExhausted();

    @Update("""
            UPDATE background_job SET status = 'CANCELLED', lease_owner = NULL, lease_until = NULL, finished_at = now(), updated_at = now()
            WHERE status = 'RUNNING' AND lease_until < now() AND cancel_requested
            """)
    int cancelAbandoned();

    @Select("""
            <script>
            SELECT * FROM background_job
            <where>
              <if test="type != null"> job_type = #{type}</if>
              <if test="status != null"> AND status = #{status}</if>
            </where>
            ORDER BY id DESC LIMIT #{limit}
            </script>
            """)
    List<JobRow> list(@Param("type") String type, @Param("status") String status, @Param("limit") int limit);

    @Update("UPDATE background_job SET status = 'QUEUED', attempts = 0, run_after = now(), last_error = NULL, cancel_requested = FALSE, finished_at = NULL, updated_at = now() WHERE id = #{id} AND status = 'FAILED'")
    int retry(long id);
}
