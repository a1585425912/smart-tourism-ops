package com.snowworld.inspection;

import com.snowworld.file.FileRef;
import java.time.OffsetDateTime;
import java.util.List;

/** 巡检接口的请求与响应结构, 字段与 docs/api-contract.md §5 一致。 */
public final class InspectionDtos {

    private InspectionDtos() {
    }

    public record TemplateItem(String code, String name, boolean required, String hint) {
    }

    public record TemplateView(long id, String name, String category, List<TemplateItem> items) {
    }

    public record TaskView(long id, String taskNo, long siteId, String siteName, Long zoneId, String zoneName,
                           long templateId, String templateName, String title, String description, String priority,
                           Long assigneeId, String assigneeName, Long reviewerId, String reviewerName,
                           OffsetDateTime dueAt, String status, String origin, boolean overdue, int issueCount,
                           int openIssueCount, int version, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    public record ResultView(String itemCode, String itemName, String result, String remark, List<FileRef> files) {
    }

    public record IssueView(long id, String itemCode, String description, String severity, String status,
                            Long rectifierId, String rectifierName, String rectifyNote, List<FileRef> rectifyFiles,
                            String reviewNote, List<FileRef> files, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    public record LogView(String action, String fromStatus, String toStatus, String actorName, String note,
                          OffsetDateTime createdAt) {
    }

    public record TaskDetail(long id, String taskNo, long siteId, String siteName, Long zoneId, String zoneName,
                             long templateId, String templateName, String title, String description, String priority,
                             Long assigneeId, String assigneeName, Long reviewerId, String reviewerName,
                             OffsetDateTime dueAt, String status, String origin, boolean overdue, int issueCount,
                             int openIssueCount, int version, OffsetDateTime createdAt, OffsetDateTime updatedAt,
                             List<TemplateItem> items, List<ResultView> results, List<IssueView> issues,
                             List<LogView> logs, List<String> allowedActions, List<String> basisRefs) {
    }

    public record CreateTask(Long siteId, Long zoneId, Long templateId, String title, String description,
                             String priority, Long assigneeId, Long reviewerId, OffsetDateTime dueAt) {
    }

    public record TransitionRequest(String action, Integer version, String note, Long assigneeId, Long reviewerId,
                                    String rectifyNote, List<Long> rectifyFileIds) {
    }

    public record ItemResultInput(String itemCode, String result, String remark, List<Long> fileIds) {
    }

    public record IssueInput(String itemCode, String description, String severity, List<Long> fileIds) {
    }

    public record ResultsRequest(Integer version, List<ItemResultInput> items, List<IssueInput> issues) {
    }

    public record Candidate(long id, String displayName) {
    }
}
