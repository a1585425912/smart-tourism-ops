package com.snowworld.inspection;

import com.snowworld.audit.AuditService;
import com.snowworld.auth.AuthUser;
import com.snowworld.auth.Permission;
import com.snowworld.auth.ScopeService;
import com.snowworld.auth.UserMapper;
import com.snowworld.common.ApiException;
import com.snowworld.common.ErrorCode;
import com.snowworld.common.PageResult;
import com.snowworld.file.FileRef;
import com.snowworld.file.FileService;
import com.snowworld.inspection.InspectionDtos.Candidate;
import com.snowworld.inspection.InspectionDtos.CreateTask;
import com.snowworld.inspection.InspectionDtos.IssueInput;
import com.snowworld.inspection.InspectionDtos.IssueView;
import com.snowworld.inspection.InspectionDtos.ItemResultInput;
import com.snowworld.inspection.InspectionDtos.LogView;
import com.snowworld.inspection.InspectionDtos.ResultView;
import com.snowworld.inspection.InspectionDtos.ResultsRequest;
import com.snowworld.inspection.InspectionDtos.TaskDetail;
import com.snowworld.inspection.InspectionDtos.TaskView;
import com.snowworld.inspection.InspectionDtos.TemplateItem;
import com.snowworld.inspection.InspectionDtos.TemplateView;
import com.snowworld.inspection.InspectionDtos.TransitionRequest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Service
public class InspectionService {

    private static final Set<String> PRIORITIES = Set.of("LOW", "NORMAL", "HIGH", "URGENT");
    private static final Set<String> RESULTS = Set.of("OK", "ABNORMAL", "NA");
    private static final Set<String> SEVERITIES = Set.of("LOW", "MEDIUM", "HIGH");
    private static final DateTimeFormatter NO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final InspectionMapper mapper;
    private final UserMapper userMapper;
    private final FileService fileService;
    private final ScopeService scope;
    private final AuditService audit;
    private final Clock clock;

    public InspectionService(InspectionMapper mapper, UserMapper userMapper, FileService fileService, ScopeService scope,
                             AuditService audit, Clock clock) {
        this.mapper = mapper;
        this.userMapper = userMapper;
        this.fileService = fileService;
        this.scope = scope;
        this.audit = audit;
        this.clock = clock;
    }

    // ---------------- 查询 ----------------

    public List<TemplateView> templates() {
        return mapper.findTemplates().stream().map(InspectionService::templateView).toList();
    }

    public PageResult<TaskView> list(List<Long> siteIds, List<String> statuses, boolean mine, String keyword,
                                     Integer page, Integer size) {
        AuthUser user = ScopeService.currentUser();
        List<Long> sites = scope.resolve(siteIds);
        int p = PageResult.clampPage(page);
        int s = PageResult.clampSize(size);
        if (sites.isEmpty()) {
            return new PageResult<>(List.of(), 0, p, s);
        }
        // 只有执行权限的巡检员只能看到本人任务
        Long assignee = mine || executorOnly(user) ? user.getId() : null;
        String kw = keyword == null || keyword.isBlank() ? null : keyword.strip();
        List<String> st = statuses == null ? List.of() : statuses;
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<TaskView> items = mapper.findTasks(sites, st, assignee, kw, s, PageResult.offset(p, s)).stream()
                .map(t -> taskView(t, now)).toList();
        return new PageResult<>(items, mapper.countTasks(sites, st, assignee, kw), p, s);
    }

    public TaskDetail detail(long id) {
        AuthUser user = ScopeService.currentUser();
        return detail(requireVisible(id, user), user);
    }

    public List<Candidate> candidates(long siteId, String role) {
        scope.requireSite(siteId);
        if (!Set.of("INSPECTOR", "REVIEWER").contains(role)) {
            throw ApiException.badRequest("role 只能为 INSPECTOR 或 REVIEWER");
        }
        List<UserMapper.UserRow> rows = new ArrayList<>(userMapper.findByRoleAndSite(role, siteId));
        if ("REVIEWER".equals(role)) {
            rows.addAll(userMapper.findByRoleAndSite("SITE_ADMIN", siteId));
        }
        Set<Long> seen = new HashSet<>();
        return rows.stream().filter(r -> seen.add(r.getId()))
                .map(r -> new Candidate(r.getId(), r.getDisplayName())).toList();
    }

    // ---------------- 创建 ----------------

    @Transactional
    public TaskDetail create(CreateTask req) {
        AuthUser user = ScopeService.currentUser();
        if (req.siteId() == null || req.templateId() == null) {
            throw ApiException.badRequest("siteId 与 templateId 不能为空");
        }
        scope.requireSite(req.siteId());
        String title = requireText(req.title(), "title", 100);
        String priority = req.priority() == null ? "NORMAL" : req.priority();
        if (!PRIORITIES.contains(priority)) {
            throw ApiException.badRequest("priority 不合法");
        }
        if (mapper.findTemplate(req.templateId()) == null) {
            throw ApiException.notFound("巡检模板");
        }
        requireZoneInSite(req.zoneId(), req.siteId());
        if (req.assigneeId() != null) {
            requireCandidate(req.siteId(), req.assigneeId(), "INSPECTOR");
        }
        if (req.reviewerId() != null) {
            requireCandidate(req.siteId(), req.reviewerId(), "REVIEWER");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        InspectionMapper.TaskRow row = new InspectionMapper.TaskRow();
        row.setTaskNo(nextTaskNo(now));
        row.setSiteId(req.siteId());
        row.setZoneId(req.zoneId());
        row.setTemplateId(req.templateId());
        row.setTitle(title);
        row.setDescription(optionalText(req.description(), "description", 500));
        row.setPriority(priority);
        row.setAssigneeId(req.assigneeId());
        row.setReviewerId(req.reviewerId());
        row.setDueAt(req.dueAt());
        row.setStatus("DRAFT");
        row.setOrigin("MANUAL");
        row.setCreatedBy(user.getId());
        row.setCreatedAt(now);
        mapper.insertTask(row);
        log(row.getId(), "CREATE", null, "DRAFT", user.getId(), null, now);
        audit.record("INSPECTION_CREATE", "INSPECTION_TASK", String.valueOf(row.getId()), req.siteId(), "OK", title);
        return detail(mapper.findTask(row.getId()), user);
    }

    /** 由 AI 助手创建草稿, 幂等。调用方(Agent)负责功能权限判定, 这里仍校验景区范围。 */
    @Transactional
    public InspectionQueryPort.DraftResult createAgentDraft(long userId, long siteId, Long zoneId, String title,
                                                            String description, List<String> basisRefs,
                                                            long agentRunId, String idempotencyKey) {
        scope.requireSite(userId, siteId);
        if (idempotencyKey != null) {
            Long existing = mapper.findIdByIdempotencyKey(idempotencyKey);
            if (existing != null) {
                return new InspectionQueryPort.DraftResult(existing, mapper.findTask(existing).getTaskNo(), false);
            }
        }
        requireZoneInSite(zoneId, siteId);
        List<InspectionMapper.TemplateRow> templates = mapper.findTemplates();
        if (templates.isEmpty()) {
            throw ApiException.notFound("巡检模板");
        }
        InspectionMapper.TemplateRow template = templates.stream()
                .filter(t -> "SERVICE".equals(t.getCategory())).findFirst().orElse(templates.getFirst());
        OffsetDateTime now = OffsetDateTime.now(clock);
        InspectionMapper.TaskRow row = new InspectionMapper.TaskRow();
        row.setTaskNo(nextTaskNo(now));
        row.setSiteId(siteId);
        row.setZoneId(zoneId);
        row.setTemplateId(template.getId());
        row.setTitle(truncate(title == null || title.isBlank() ? "AI 建议巡检" : title.strip(), 100));
        row.setDescription(description == null ? null : truncate(description.strip(), 500));
        row.setPriority("NORMAL");
        row.setStatus("DRAFT");
        row.setOrigin("AGENT_DRAFT");
        row.setAgentRunId(agentRunId);
        row.setBasisRefs(com.snowworld.job.JobService.tree(basisRefs == null ? List.of() : basisRefs));
        row.setIdempotencyKey(idempotencyKey);
        row.setCreatedBy(userId);
        row.setCreatedAt(now);
        mapper.insertTask(row);
        log(row.getId(), "AGENT_DRAFT", null, "DRAFT", userId, "AI 运营助手创建草稿，任务 " + agentRunId, now);
        UserMapper.UserRow actor = userMapper.findById(userId);
        audit.record(userId, actor == null ? null : actor.getUsername(), "INSPECTION_AGENT_DRAFT", "INSPECTION_TASK",
                String.valueOf(row.getId()), siteId, "OK", "agentRun=" + agentRunId);
        return new InspectionQueryPort.DraftResult(row.getId(), row.getTaskNo(), true);
    }

    // ---------------- 状态迁移 ----------------

    @Transactional
    public TaskDetail transition(long id, TransitionRequest req) {
        AuthUser user = ScopeService.currentUser();
        InspectionMapper.TaskRow task = requireVisible(id, user);
        String action = req.action() == null ? "" : req.action();
        InspectionStateMachine.Transition t = InspectionStateMachine.TRANSITIONS.get(action);
        if (t == null) {
            throw ApiException.badRequest("未知的操作: " + action);
        }
        if (req.version() == null) {
            throw ApiException.badRequest("version 不能为空");
        }
        if (!InspectionStateMachine.allowedFrom(action, task.getStatus())) {
            throw new ApiException(ErrorCode.ILLEGAL_TRANSITION,
                    "当前状态 " + task.getStatus() + " 不允许执行 " + action);
        }
        if (!InspectionStateMachine.actorAllowed(action, user, task)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "当前用户无权对该任务执行 " + action);
        }
        if (!Objects.equals(task.getVersion(), req.version())) {
            throw new ApiException(ErrorCode.CONFLICT);
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        String note = optionalText(req.note(), "note", 500);
        Long assigneeId = null;
        Long reviewerId = null;
        int openIssues = (int) mapper.findIssues(id).stream().filter(i -> "OPEN".equals(i.getStatus())).count();
        switch (action) {
            case "ASSIGN" -> {
                if (req.assigneeId() == null && task.getAssigneeId() == null) {
                    throw ApiException.badRequest("派发需要指定执行人");
                }
                assigneeId = req.assigneeId() == null ? task.getAssigneeId() : req.assigneeId();
                requireCandidate(task.getSiteId(), assigneeId, "INSPECTOR");
                if (req.reviewerId() != null) {
                    requireCandidate(task.getSiteId(), req.reviewerId(), "REVIEWER");
                    reviewerId = req.reviewerId();
                }
            }
            case "SUBMIT" -> requireRequiredItems(task);
            case "CONFIRM" -> {
                if (openIssues > 0) {
                    throw new ApiException(ErrorCode.ILLEGAL_TRANSITION, "存在未关闭的异常，请先要求整改");
                }
            }
            case "REQUIRE_RECTIFY" -> {
                if (openIssues == 0) {
                    throw new ApiException(ErrorCode.ILLEGAL_TRANSITION, "没有待整改的异常");
                }
                requireNote(note, "要求整改需要填写说明");
                mapper.assignRectifier(id, task.getAssigneeId(), now);
            }
            case "RECTIFY_DONE" -> {
                String rectifyNote = requireText(req.rectifyNote(), "rectifyNote", 500);
                List<Long> files = fileService.requireAttachable(req.rectifyFileIds(), task.getSiteId(), user.getId(), id);
                mapper.markRectified(id, user.getId(), rectifyNote, files.toArray(Long[]::new), now);
                note = note == null ? rectifyNote : note;
            }
            case "APPROVE" -> mapper.closeRectified(id, note, now);
            case "REJECT" -> {
                requireNote(note, "退回整改需要填写原因");
                mapper.reopen(id, note, now);
            }
            default -> {
                // START / CANCEL 无额外前置条件
            }
        }
        if (mapper.transition(id, req.version(), t.to(), assigneeId, reviewerId, now) == 0) {
            throw new ApiException(ErrorCode.CONFLICT);
        }
        log(id, action, task.getStatus(), t.to(), user.getId(), note, now);
        audit.record("INSPECTION_" + action, "INSPECTION_TASK", String.valueOf(id), task.getSiteId(), "OK",
                task.getStatus() + "->" + t.to());
        return detail(mapper.findTask(id), user);
    }

    // ---------------- 结果提交 ----------------

    @Transactional
    public TaskDetail saveResults(long id, ResultsRequest req) {
        AuthUser user = ScopeService.currentUser();
        InspectionMapper.TaskRow task = requireVisible(id, user);
        if (!"IN_PROGRESS".equals(task.getStatus())) {
            throw new ApiException(ErrorCode.ILLEGAL_TRANSITION, "只有执行中的任务可以填写结果");
        }
        if (task.getAssigneeId() == null || task.getAssigneeId() != user.getId()
                || !user.has(Permission.INSPECTION_EXECUTE)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "只有任务执行人可以填写结果");
        }
        if (req.version() == null) {
            throw ApiException.badRequest("version 不能为空");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (mapper.touch(id, req.version(), now) == 0) {
            throw new ApiException(ErrorCode.CONFLICT);
        }
        Map<String, TemplateItem> items = itemMap(mapper.findTemplate(task.getTemplateId()));
        List<ItemResultInput> inputs = req.items() == null ? List.of() : req.items();
        Set<String> seen = new HashSet<>();
        for (ItemResultInput in : inputs) {
            TemplateItem item = items.get(in.itemCode());
            if (item == null) {
                throw ApiException.badRequest("检查项不存在: " + in.itemCode());
            }
            if (!seen.add(in.itemCode())) {
                throw ApiException.badRequest("检查项重复: " + in.itemCode());
            }
            if (!RESULTS.contains(in.result())) {
                throw ApiException.badRequest("检查结果不合法: " + in.result());
            }
            InspectionMapper.ResultRow row = new InspectionMapper.ResultRow();
            row.setTaskId(id);
            row.setItemCode(item.code());
            row.setItemName(item.name());
            row.setResult(in.result());
            row.setRemark(optionalText(in.remark(), "remark", 500));
            row.setFileIds(fileService.requireAttachable(in.fileIds(), task.getSiteId(), user.getId(), id)
                    .toArray(Long[]::new));
            mapper.upsertResult(row);
        }
        if (req.issues() != null) {
            mapper.deleteIssues(id);
            for (IssueInput in : req.issues()) {
                if (in.itemCode() != null && !items.containsKey(in.itemCode())) {
                    throw ApiException.badRequest("异常关联的检查项不存在: " + in.itemCode());
                }
                String severity = in.severity() == null ? "MEDIUM" : in.severity();
                if (!SEVERITIES.contains(severity)) {
                    throw ApiException.badRequest("severity 不合法");
                }
                InspectionMapper.IssueRow row = new InspectionMapper.IssueRow();
                row.setTaskId(id);
                row.setItemCode(in.itemCode());
                row.setDescription(requireText(in.description(), "异常描述", 500));
                row.setSeverity(severity);
                row.setFileIds(fileService.requireAttachable(in.fileIds(), task.getSiteId(), user.getId(), id)
                        .toArray(Long[]::new));
                row.setCreatedAt(now);
                mapper.insertIssue(row);
            }
        }
        log(id, "SAVE_RESULTS", task.getStatus(), task.getStatus(), user.getId(),
                "保存 " + inputs.size() + " 项结果", now);
        return detail(mapper.findTask(id), user);
    }

    // ---------------- 内部方法 ----------------

    InspectionMapper.TaskRow requireVisible(long id, AuthUser user) {
        InspectionMapper.TaskRow task = mapper.findTask(id);
        if (task == null) {
            throw ApiException.notFound("巡检任务");
        }
        scope.requireSite(user.getId(), task.getSiteId());
        if (executorOnly(user) && (task.getAssigneeId() == null || task.getAssigneeId() != user.getId())) {
            throw new ApiException(ErrorCode.FORBIDDEN, "只能查看本人执行的巡检任务");
        }
        return task;
    }

    private static boolean executorOnly(AuthUser user) {
        return user.has(Permission.INSPECTION_EXECUTE)
                && !user.has(Permission.INSPECTION_MANAGE) && !user.has(Permission.INSPECTION_REVIEW);
    }

    private TaskDetail detail(InspectionMapper.TaskRow t, AuthUser user) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<InspectionMapper.ResultRow> results = mapper.findResults(t.getId());
        List<InspectionMapper.IssueRow> issues = mapper.findIssues(t.getId());
        List<Long> fileIds = Stream.concat(
                results.stream().flatMap(r -> ids(r.getFileIds())),
                issues.stream().flatMap(i -> Stream.concat(ids(i.getFileIds()), ids(i.getRectifyFiles())))).toList();
        Map<Long, FileRef> files = fileService.refs(fileIds);
        int openIssues = (int) issues.stream().filter(i -> "OPEN".equals(i.getStatus())).count();
        List<String> basis = new ArrayList<>();
        JsonNode refs = t.getBasisRefs();
        if (refs != null && refs.isArray()) {
            refs.forEach(n -> basis.add(n.asString()));
        }
        TaskView v = taskView(t, now);
        return new TaskDetail(v.id(), v.taskNo(), v.siteId(), v.siteName(), v.zoneId(), v.zoneName(), v.templateId(),
                v.templateName(), v.title(), v.description(), v.priority(), v.assigneeId(), v.assigneeName(),
                v.reviewerId(), v.reviewerName(), v.dueAt(), v.status(), v.origin(), v.overdue(), v.issueCount(),
                v.openIssueCount(), v.version(), v.createdAt(), v.updatedAt(),
                templateView(mapper.findTemplate(t.getTemplateId())).items(),
                results.stream().map(r -> new ResultView(r.getItemCode(), r.getItemName(), r.getResult(), r.getRemark(),
                        refs(r.getFileIds(), files))).toList(),
                issues.stream().map(i -> new IssueView(i.getId(), i.getItemCode(), i.getDescription(), i.getSeverity(),
                        i.getStatus(), i.getRectifierId(), i.getRectifierName(), i.getRectifyNote(),
                        refs(i.getRectifyFiles(), files), i.getReviewNote(), refs(i.getFileIds(), files),
                        i.getCreatedAt(), i.getUpdatedAt())).toList(),
                mapper.findLogs(t.getId()).stream().map(l -> new LogView(l.getAction(), l.getFromStatus(),
                        l.getToStatus(), l.getActorName(), l.getNote(), l.getCreatedAt())).toList(),
                InspectionStateMachine.allowedActions(user, t, openIssues),
                refs == null ? null : basis);
    }

    static TaskView taskView(InspectionMapper.TaskRow t, OffsetDateTime now) {
        boolean overdue = t.getDueAt() != null && t.getDueAt().isBefore(now)
                && !Set.of("COMPLETED", "CANCELLED").contains(t.getStatus());
        return new TaskView(t.getId(), t.getTaskNo(), t.getSiteId(), t.getSiteName(), t.getZoneId(), t.getZoneName(),
                t.getTemplateId(), t.getTemplateName(), t.getTitle(), t.getDescription(), t.getPriority(),
                t.getAssigneeId(), t.getAssigneeName(), t.getReviewerId(), t.getReviewerName(), t.getDueAt(),
                t.getStatus(), t.getOrigin(), overdue, nz(t.getIssueCount()), nz(t.getOpenIssueCount()),
                nz(t.getVersion()), t.getCreatedAt(), t.getUpdatedAt());
    }

    static TemplateView templateView(InspectionMapper.TemplateRow row) {
        List<TemplateItem> items = new ArrayList<>();
        if (row.getItems() != null) {
            row.getItems().forEach(n -> items.add(new TemplateItem(n.path("code").asString(), n.path("name").asString(),
                    n.path("required").asBoolean(false), n.path("hint").isMissingNode() ? null : n.path("hint").asString())));
        }
        return new TemplateView(row.getId(), row.getName(), row.getCategory(), items);
    }

    private static Map<String, TemplateItem> itemMap(InspectionMapper.TemplateRow template) {
        Map<String, TemplateItem> out = new HashMap<>();
        templateView(template).items().forEach(i -> out.put(i.code(), i));
        return out;
    }

    private void requireRequiredItems(InspectionMapper.TaskRow task) {
        Set<String> answered = new HashSet<>();
        mapper.findResults(task.getId()).forEach(r -> answered.add(r.getItemCode()));
        List<String> missing = templateView(mapper.findTemplate(task.getTemplateId())).items().stream()
                .filter(i -> i.required() && !answered.contains(i.code())).map(TemplateItem::name).toList();
        if (!missing.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "必填检查项未填写: " + String.join("、", missing),
                    Map.of("missingItems", missing));
        }
    }

    private void requireZoneInSite(Long zoneId, long siteId) {
        if (zoneId == null) {
            return;
        }
        Long zoneSite = mapper.zoneSite(zoneId);
        if (zoneSite == null || zoneSite != siteId) {
            throw ApiException.badRequest("区域不存在或不属于该景区");
        }
    }

    private void requireCandidate(long siteId, long userId, String role) {
        boolean ok = userMapper.findByRoleAndSite(role, siteId).stream().anyMatch(u -> u.getId() == userId)
                || ("REVIEWER".equals(role)
                && userMapper.findByRoleAndSite("SITE_ADMIN", siteId).stream().anyMatch(u -> u.getId() == userId));
        if (!ok) {
            throw ApiException.badRequest(("INSPECTOR".equals(role) ? "执行人" : "复核人") + "不是该景区的有效人员");
        }
    }

    private String nextTaskNo(OffsetDateTime now) {
        String prefix = "XJ" + now.format(NO_DATE) + "-";
        return prefix + String.format("%04d", mapper.nextSeq(prefix));
    }

    private void log(long taskId, String action, String from, String to, Long actorId, String note, OffsetDateTime at) {
        InspectionMapper.LogRow row = new InspectionMapper.LogRow();
        row.setTaskId(taskId);
        row.setAction(action);
        row.setFromStatus(from);
        row.setToStatus(to);
        row.setActorId(actorId);
        row.setNote(note);
        row.setCreatedAt(at);
        mapper.insertLog(row);
    }

    private static Stream<Long> ids(Long[] arr) {
        return arr == null ? Stream.empty() : Arrays.stream(arr);
    }

    private static List<FileRef> refs(Long[] arr, Map<Long, FileRef> files) {
        return ids(arr).map(files::get).filter(Objects::nonNull).toList();
    }

    private static void requireNote(String note, String message) {
        if (note == null) {
            throw ApiException.badRequest(message);
        }
    }

    private static String requireText(String value, String field, int max) {
        String v = optionalText(value, field, max);
        if (v == null) {
            throw ApiException.badRequest(field + " 不能为空");
        }
        return v;
    }

    private static String optionalText(String value, String field, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.strip();
        if (v.length() > max) {
            throw ApiException.badRequest(field + " 不能超过 " + max + " 字");
        }
        return v;
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
