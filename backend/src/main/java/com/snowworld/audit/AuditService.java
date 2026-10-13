package com.snowworld.audit;

import com.snowworld.auth.AuthUser;
import com.snowworld.common.TraceIdFilter;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.Data;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 审计日志: 登录、授权变更、状态迁移、AI 写草稿等关键操作。审计失败不影响主流程。 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditMapper mapper;

    public AuditService(AuditMapper mapper) {
        this.mapper = mapper;
    }

    public void record(String action, String targetType, String targetId, Long siteId, String outcome, String detail) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthUser u) {
            record(u.getId(), u.getUsername(), action, targetType, targetId, siteId, outcome, detail);
        } else {
            record(null, "system", action, targetType, targetId, siteId, outcome, detail);
        }
    }

    public void record(Long actorId, String actorName, String action, String targetType, String targetId, Long siteId,
                       String outcome, String detail) {
        try {
            AuditRow row = new AuditRow();
            row.setActorId(actorId);
            row.setActorName(actorName);
            row.setAction(action);
            row.setTargetType(targetType);
            row.setTargetId(targetId);
            row.setSiteId(siteId);
            row.setOutcome(outcome);
            row.setDetail(detail == null ? null : detail.substring(0, Math.min(detail.length(), 1000)));
            row.setTraceId(TraceIdFilter.current());
            mapper.insert(row);
        } catch (RuntimeException e) {
            log.warn("audit write failed: {}", e.getMessage());
        }
    }

    @Data
    public static class AuditRow {
        private Long id;
        private Long actorId;
        private String actorName;
        private String action;
        private String targetType;
        private String targetId;
        private Long siteId;
        private String outcome;
        private String detail;
        private String traceId;
        private OffsetDateTime createdAt;
    }

    @Mapper
    public interface AuditMapper {
        @Insert("""
                INSERT INTO audit_log (actor_id, actor_name, action, target_type, target_id, site_id, outcome, detail, trace_id)
                VALUES (#{actorId}, #{actorName}, #{action}, #{targetType}, #{targetId}, #{siteId}, #{outcome}, #{detail}, #{traceId})
                """)
        void insert(AuditRow row);

        @Select("SELECT * FROM audit_log ORDER BY id DESC LIMIT #{limit}")
        List<AuditRow> latest(@Param("limit") int limit);
    }

    @RestController
    public static class AuditController {
        private final AuditMapper mapper;

        public AuditController(AuditMapper mapper) {
            this.mapper = mapper;
        }

        @GetMapping("/api/v1/audit-logs")
        @PreAuthorize("hasAuthority('AUDIT_READ')")
        public List<AuditRow> latest(@RequestParam(defaultValue = "100") int limit) {
            return mapper.latest(Math.min(Math.max(limit, 1), 500));
        }
    }
}
