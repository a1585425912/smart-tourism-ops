package com.snowworld.agent;

import java.time.LocalDate;
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
public interface AgentRunMapper {

    @Data
    class RunRow {
        private Long id;
        private Long userId;
        private String scenario;
        private Long[] siteScope;
        private JsonNode input;
        private String status;
        private String progress;
        private JsonNode result;
        private String errorCode;
        private String errorMessage;
        private String provider;
        private String model;
        private String promptVersion;
        private Integer modelCalls;
        private Integer toolCalls;
        private Integer tokensIn;
        private Integer tokensOut;
        private Boolean cancelRequested;
        private String idempotencyKey;
        private OffsetDateTime createdAt;
        private OffsetDateTime startedAt;
        private OffsetDateTime finishedAt;
    }

    @Data
    class ToolCallRow {
        private Long id;
        private Long runId;
        private Integer seq;
        private String callId;
        private String toolName;
        private JsonNode arguments;
        private String status;
        private String resultSummary;
        private JsonNode result;
        private String[] sourceIds;
        private Integer durationMs;
        private OffsetDateTime createdAt;
    }

    @Data
    class UsageRow {
        private Integer runs;
        private Integer tokens;
    }

    @Insert("""
            INSERT INTO agent_run (user_id, scenario, site_scope, input, provider, model, prompt_version, idempotency_key)
            VALUES (#{userId}, #{scenario}, #{siteScope}, #{input}, #{provider}, #{model}, #{promptVersion}, #{idempotencyKey})
            ON CONFLICT (idempotency_key) DO NOTHING
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(RunRow row);

    @Select("SELECT * FROM agent_run WHERE id = #{id}")
    RunRow findById(long id);

    @Select("SELECT * FROM agent_run WHERE idempotency_key = #{key}")
    RunRow findByKey(String key);

    @Select("""
            <script>
            SELECT * FROM agent_run WHERE user_id = #{userId}
            <if test="scenario != null"> AND scenario = #{scenario}</if>
            ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<RunRow> listMine(@Param("userId") long userId, @Param("scenario") String scenario,
                          @Param("limit") int limit, @Param("offset") int offset);

    @Select("""
            <script>
            SELECT count(*) FROM agent_run WHERE user_id = #{userId}
            <if test="scenario != null"> AND scenario = #{scenario}</if>
            </script>
            """)
    long countMine(@Param("userId") long userId, @Param("scenario") String scenario);

    /** 进入运行状态; 已结束的任务不会被重新启动。 */
    @Update("""
            UPDATE agent_run SET status = 'RUNNING', started_at = coalesce(started_at, #{now}), progress = '开始执行',
                model_calls = 0, tool_calls = 0, tokens_in = 0, tokens_out = 0
            WHERE id = #{id} AND status IN ('QUEUED', 'RUNNING')
            """)
    int start(@Param("id") long id, @Param("now") OffsetDateTime now);

    @Update("UPDATE agent_run SET progress = #{progress} WHERE id = #{id} AND status = 'RUNNING'")
    int progress(@Param("id") long id, @Param("progress") String progress);

    @Update("""
            UPDATE agent_run SET model_calls = model_calls + 1, tokens_in = tokens_in + #{in}, tokens_out = tokens_out + #{out},
                model = coalesce(#{model}, model)
            WHERE id = #{id}
            """)
    int addModelCall(@Param("id") long id, @Param("in") int tokensIn, @Param("out") int tokensOut, @Param("model") String model);

    @Update("UPDATE agent_run SET tool_calls = tool_calls + 1 WHERE id = #{id}")
    int addToolCall(long id);

    @Update("""
            UPDATE agent_run SET status = #{status}, result = #{result}, error_code = #{errorCode}, error_message = #{errorMessage},
                progress = #{progress}, finished_at = #{now}
            WHERE id = #{id} AND status IN ('QUEUED', 'RUNNING')
            """)
    int finish(@Param("id") long id, @Param("status") String status, @Param("result") JsonNode result,
               @Param("errorCode") String errorCode, @Param("errorMessage") String errorMessage,
               @Param("progress") String progress, @Param("now") OffsetDateTime now);

    @Update("UPDATE agent_run SET cancel_requested = TRUE WHERE id = #{id} AND status IN ('QUEUED', 'RUNNING')")
    int requestCancel(long id);

    @Select("SELECT cancel_requested FROM agent_run WHERE id = #{id}")
    boolean isCancelRequested(long id);

    @Update("""
            UPDATE agent_run r SET
                status = CASE WHEN j.status = 'CANCELLED' THEN 'CANCELLED' ELSE 'FAILED' END,
                error_code = CASE WHEN j.status = 'CANCELLED' THEN NULL ELSE 'INTERRUPTED' END,
                error_message = CASE WHEN j.status = 'CANCELLED' THEN NULL ELSE '任务执行中断，请重试' END,
                progress = CASE WHEN j.status = 'CANCELLED' THEN '已取消' ELSE '失败' END,
                finished_at = now()
            FROM background_job j
            WHERE j.idempotency_key = 'agent-run:' || r.id AND r.status IN ('QUEUED', 'RUNNING')
              AND j.status IN ('FAILED', 'CANCELLED')
            """)
    int reconcileInterrupted();

    // ---------- 工具调用与消息 ----------

    @Insert("""
            INSERT INTO agent_tool_call (run_id, seq, call_id, tool_name, arguments, status, result_summary, result, source_ids, duration_ms)
            VALUES (#{runId}, #{seq}, #{callId}, #{toolName}, #{arguments}, #{status}, #{resultSummary}, #{result}, #{sourceIds}, #{durationMs})
            """)
    void insertToolCall(ToolCallRow row);

    @Select("SELECT * FROM agent_tool_call WHERE run_id = #{runId} AND seq > #{afterSeq} ORDER BY seq")
    List<ToolCallRow> toolCalls(@Param("runId") long runId, @Param("afterSeq") int afterSeq);

    @Delete("DELETE FROM agent_tool_call WHERE run_id = #{runId}")
    void clearToolCalls(long runId);

    @Delete("DELETE FROM agent_message WHERE run_id = #{runId}")
    void clearMessages(long runId);

    @Insert("INSERT INTO agent_message (run_id, seq, role, content, tool_call_id) VALUES (#{runId}, #{seq}, #{role}, #{content}, #{toolCallId})")
    void insertMessage(@Param("runId") long runId, @Param("seq") int seq, @Param("role") String role,
                       @Param("content") String content, @Param("toolCallId") String toolCallId);

    // ---------- 额度 ----------

    @Select("SELECT runs, tokens FROM ai_usage_daily WHERE user_id = #{userId} AND biz_date = #{date}")
    UsageRow usage(@Param("userId") long userId, @Param("date") LocalDate date);

    @Insert("""
            INSERT INTO ai_usage_daily (user_id, biz_date, runs, tokens) VALUES (#{userId}, #{date}, #{runs}, #{tokens})
            ON CONFLICT (user_id, biz_date) DO UPDATE SET runs = ai_usage_daily.runs + #{runs}, tokens = ai_usage_daily.tokens + #{tokens}
            """)
    void addUsage(@Param("userId") long userId, @Param("date") LocalDate date, @Param("runs") int runs, @Param("tokens") int tokens);
}
