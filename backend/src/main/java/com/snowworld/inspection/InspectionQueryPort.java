package com.snowworld.inspection;

import com.snowworld.common.TimeRange;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** 巡检模块对外契约: 供运营日报与 Agent 工具读取汇总、创建草稿(不能直接派单)。 */
public interface InspectionQueryPort {

    InspectionSummary summary(List<Long> siteIds, TimeRange range);

    /**
     * 由 AI 助手创建巡检草稿, 状态固定为 DRAFT, 需人工派发。相同 idempotencyKey 只创建一次。
     *
     * @param basisRefs 依据来源 ID(如 review:123, flow:site:1:2026-02-18)
     */
    DraftResult createAgentDraft(long userId, long siteId, Long zoneId, String title, String description,
                                 List<String> basisRefs, long agentRunId, String idempotencyKey);

    record OverdueTask(long taskId, String taskNo, long siteId, String title, String status,
                       OffsetDateTime dueAt, String assigneeName) {
    }

    record OpenIssue(long issueId, long taskId, String taskNo, long siteId, String description, String severity,
                     String status, OffsetDateTime createdAt) {
    }

    record InspectionSummary(
            /** 创建时间落在区间内的任务按状态计数 */
            Map<String, Integer> byStatus,
            int total,
            int completed,
            int overdue,
            int openIssues,
            List<OverdueTask> overdueTasks,
            List<OpenIssue> issues,
            OffsetDateTime dataAsOf) {
    }

    record DraftResult(long taskId, String taskNo, boolean created) {
    }
}
