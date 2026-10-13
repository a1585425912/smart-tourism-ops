package com.snowworld.auth;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** 功能权限。角色只决定"能做什么", 景区数据范围由 {@link ScopeService} 另行判定。 */
public enum Permission {
    SITE_READ, SITE_WRITE,
    DEVICE_READ, DEVICE_WRITE, VIDEO_VIEW,
    FLOW_READ, FLOW_IMPORT,
    REVIEW_READ, REVIEW_WRITE, REVIEW_IMPORT,
    INSPECTION_READ, INSPECTION_MANAGE, INSPECTION_EXECUTE, INSPECTION_REVIEW,
    BUSINESS_READ,
    AI_USE, AI_DRAFT_INSPECTION, MARKETING_WRITE,
    REPORT_READ, REPORT_WRITE,
    INTEGRATION_READ, INTEGRATION_MANAGE,
    USER_MANAGE, AUDIT_READ;

    private static final Map<String, Set<Permission>> ROLE_PERMISSIONS = Map.of(
            "GROUP_ADMIN", EnumSet.allOf(Permission.class),
            "SITE_ADMIN", EnumSet.of(SITE_READ, SITE_WRITE, DEVICE_READ, DEVICE_WRITE, VIDEO_VIEW, FLOW_READ, FLOW_IMPORT,
                    REVIEW_READ, REVIEW_WRITE, REVIEW_IMPORT, INSPECTION_READ, INSPECTION_MANAGE, INSPECTION_REVIEW,
                    BUSINESS_READ, AI_USE, AI_DRAFT_INSPECTION, MARKETING_WRITE, REPORT_READ, REPORT_WRITE,
                    INTEGRATION_READ, INTEGRATION_MANAGE, AUDIT_READ),
            "OPERATOR", EnumSet.of(SITE_READ, DEVICE_READ, VIDEO_VIEW, FLOW_READ, FLOW_IMPORT, REVIEW_READ, REVIEW_WRITE,
                    REVIEW_IMPORT, INSPECTION_READ, BUSINESS_READ, AI_USE, MARKETING_WRITE, REPORT_READ, REPORT_WRITE,
                    INTEGRATION_READ),
            "INSPECTOR", EnumSet.of(SITE_READ, DEVICE_READ, INSPECTION_READ, INSPECTION_EXECUTE),
            "REVIEWER", EnumSet.of(SITE_READ, DEVICE_READ, INSPECTION_READ, INSPECTION_REVIEW, REPORT_READ),
            "VIEWER", EnumSet.of(SITE_READ, DEVICE_READ, VIDEO_VIEW, FLOW_READ, REVIEW_READ, BUSINESS_READ, REPORT_READ));

    public static Set<Permission> forRoles(Iterable<String> roles) {
        Set<Permission> out = new LinkedHashSet<>();
        for (String role : roles) {
            out.addAll(ROLE_PERMISSIONS.getOrDefault(role, Set.of()));
        }
        return out;
    }
}
