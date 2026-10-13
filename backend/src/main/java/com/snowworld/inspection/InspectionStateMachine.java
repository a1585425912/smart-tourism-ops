package com.snowworld.inspection;

import com.snowworld.auth.AuthUser;
import com.snowworld.auth.Permission;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 巡检任务状态机。允许的迁移只在这里定义, 客户端只能提交 action, 不能直接写状态。
 *
 * <pre>
 * DRAFT --ASSIGN--> ASSIGNED --START--> IN_PROGRESS --SUBMIT--> SUBMITTED
 * SUBMITTED --CONFIRM--> COMPLETED (无未关闭异常)   SUBMITTED --REQUIRE_RECTIFY--> RECTIFYING (存在异常)
 * RECTIFYING --RECTIFY_DONE--> PENDING_REVIEW --APPROVE--> COMPLETED
 * PENDING_REVIEW --REJECT--> RECTIFYING          DRAFT/ASSIGNED --CANCEL--> CANCELLED
 * </pre>
 */
public final class InspectionStateMachine {

    public record Transition(String action, Set<String> from, String to) {
    }

    public static final Map<String, Transition> TRANSITIONS = Map.of(
            "ASSIGN", new Transition("ASSIGN", Set.of("DRAFT"), "ASSIGNED"),
            "START", new Transition("START", Set.of("ASSIGNED"), "IN_PROGRESS"),
            "SUBMIT", new Transition("SUBMIT", Set.of("IN_PROGRESS"), "SUBMITTED"),
            "CONFIRM", new Transition("CONFIRM", Set.of("SUBMITTED"), "COMPLETED"),
            "REQUIRE_RECTIFY", new Transition("REQUIRE_RECTIFY", Set.of("SUBMITTED"), "RECTIFYING"),
            "RECTIFY_DONE", new Transition("RECTIFY_DONE", Set.of("RECTIFYING"), "PENDING_REVIEW"),
            "APPROVE", new Transition("APPROVE", Set.of("PENDING_REVIEW"), "COMPLETED"),
            "REJECT", new Transition("REJECT", Set.of("PENDING_REVIEW"), "RECTIFYING"),
            "CANCEL", new Transition("CANCEL", Set.of("DRAFT", "ASSIGNED"), "CANCELLED"));

    /** 前端按钮的展示顺序。 */
    private static final List<String> ORDER = List.of("ASSIGN", "START", "SUBMIT", "CONFIRM", "REQUIRE_RECTIFY",
            "RECTIFY_DONE", "APPROVE", "REJECT", "CANCEL");

    private InspectionStateMachine() {
    }

    /** 状态上是否允许(不含角色判定)。 */
    public static boolean allowedFrom(String action, String status) {
        Transition t = TRANSITIONS.get(action);
        return t != null && t.from().contains(status);
    }

    /** 角色与任务归属判定。 */
    public static boolean actorAllowed(String action, AuthUser user, InspectionMapper.TaskRow task) {
        boolean manage = user.has(Permission.INSPECTION_MANAGE);
        boolean isAssignee = task.getAssigneeId() != null && task.getAssigneeId() == user.getId();
        boolean isReviewer = user.has(Permission.INSPECTION_REVIEW)
                && (task.getReviewerId() == null || task.getReviewerId() == user.getId() || manage);
        return switch (action) {
            case "ASSIGN", "CANCEL" -> manage;
            case "START", "SUBMIT" -> isAssignee && user.has(Permission.INSPECTION_EXECUTE);
            case "CONFIRM", "REQUIRE_RECTIFY", "APPROVE", "REJECT" -> isReviewer;
            case "RECTIFY_DONE" -> manage || (isAssignee && user.has(Permission.INSPECTION_EXECUTE));
            default -> false;
        };
    }

    /**
     * 当前用户对该任务可执行的动作。业务前置条件(如 CONFIRM 要求无未关闭异常)一并考虑, 避免显示必然失败的按钮。
     */
    public static List<String> allowedActions(AuthUser user, InspectionMapper.TaskRow task, int openIssues) {
        List<String> out = new ArrayList<>();
        for (String action : ORDER) {
            if (!allowedFrom(action, task.getStatus()) || !actorAllowed(action, user, task)) {
                continue;
            }
            if ("CONFIRM".equals(action) && openIssues > 0) {
                continue;
            }
            if ("REQUIRE_RECTIFY".equals(action) && openIssues == 0) {
                continue;
            }
            out.add(action);
        }
        return out;
    }
}
