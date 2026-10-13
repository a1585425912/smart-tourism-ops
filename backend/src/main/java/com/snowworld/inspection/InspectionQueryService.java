package com.snowworld.inspection;

import com.snowworld.common.TimeRange;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** {@link InspectionQueryPort} 实现。调用方负责景区范围校验(Agent 工具在调用前已按用户范围裁剪 siteIds)。 */
@Service
public class InspectionQueryService implements InspectionQueryPort {

    private static final int LIST_LIMIT = 20;

    private final InspectionMapper mapper;
    private final InspectionService inspectionService;
    private final Clock clock;

    public InspectionQueryService(InspectionMapper mapper, InspectionService inspectionService, Clock clock) {
        this.mapper = mapper;
        this.inspectionService = inspectionService;
        this.clock = clock;
    }

    @Override
    public InspectionSummary summary(List<Long> siteIds, TimeRange range) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (siteIds == null || siteIds.isEmpty()) {
            return new InspectionSummary(Map.of(), 0, 0, 0, 0, List.of(), List.of(), null);
        }
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        mapper.countByStatus(siteIds, range.start(), range.end()).forEach(c -> byStatus.put(c.getStatus(), c.getCnt()));
        int total = byStatus.values().stream().mapToInt(Integer::intValue).sum();
        List<InspectionMapper.TaskRow> overdue = mapper.findOverdue(siteIds, now);
        List<InspectionMapper.OpenIssueRow> issues = mapper.findOpenIssues(siteIds);
        return new InspectionSummary(byStatus, total, byStatus.getOrDefault("COMPLETED", 0), overdue.size(),
                issues.size(),
                overdue.stream().limit(LIST_LIMIT).map(t -> new OverdueTask(t.getId(), t.getTaskNo(), t.getSiteId(),
                        t.getTitle(), t.getStatus(), t.getDueAt(), t.getAssigneeName())).toList(),
                issues.stream().limit(LIST_LIMIT).map(i -> new OpenIssue(i.getId(), i.getTaskId(), i.getTaskNo(),
                        i.getSiteId(), i.getDescription(), i.getSeverity(), i.getStatus(), i.getCreatedAt())).toList(),
                mapper.latestUpdate(siteIds));
    }

    @Override
    public DraftResult createAgentDraft(long userId, long siteId, Long zoneId, String title, String description,
                                        List<String> basisRefs, long agentRunId, String idempotencyKey) {
        return inspectionService.createAgentDraft(userId, siteId, zoneId, title, description, basisRefs, agentRunId,
                idempotencyKey);
    }
}
