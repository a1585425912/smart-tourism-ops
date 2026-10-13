package com.snowworld.marketing;

import com.snowworld.audit.AuditService;
import com.snowworld.auth.ScopeService;
import com.snowworld.common.ApiException;
import com.snowworld.common.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 营销方案草稿。方案由 Agent 的 MARKETING 场景生成(POST /agent/runs); 这里只做查看与内部状态流转,
 * 不对接任何外部渠道, 不会创建真实营销活动。
 */
@RestController
@RequestMapping("/api/v1/marketing-plans")
@PreAuthorize("hasAuthority('MARKETING_WRITE')")
public class MarketingController {

    private final MarketingMapper mapper;
    private final ScopeService scope;
    private final AuditService audit;

    public MarketingController(MarketingMapper mapper, ScopeService scope, AuditService audit) {
        this.mapper = mapper;
        this.scope = scope;
        this.audit = audit;
    }

    @GetMapping
    public List<MarketingMapper.PlanRow> list() {
        return mapper.list(scope.allowedSiteIds().toArray(Long[]::new));
    }

    @GetMapping("/{id}")
    public MarketingMapper.PlanRow detail(@PathVariable long id) {
        return require(id);
    }

    @PatchMapping("/{id}/status")
    public MarketingMapper.PlanRow updateStatus(@PathVariable long id, @RequestBody Map<String, String> body) {
        MarketingMapper.PlanRow plan = require(id);
        String status = body == null ? null : body.get("status");
        if (status == null || !Set.of("APPROVED", "ARCHIVED").contains(status)) {
            throw ApiException.badRequest("status 只能为 APPROVED 或 ARCHIVED");
        }
        if ("ARCHIVED".equals(plan.getStatus())) {
            throw new ApiException(ErrorCode.ILLEGAL_TRANSITION, "已归档的方案不能再变更状态");
        }
        mapper.updateStatus(id, status);
        audit.record("MARKETING_PLAN_STATUS", "MARKETING_PLAN", String.valueOf(id), null, "OK", plan.getStatus() + " -> " + status);
        return mapper.findById(id);
    }

    private MarketingMapper.PlanRow require(long id) {
        MarketingMapper.PlanRow plan = mapper.findById(id);
        if (plan == null) {
            throw ApiException.notFound("营销方案");
        }
        List<Long> allowed = scope.allowedSiteIds();
        for (Long siteId : plan.getSiteIds()) {
            if (!allowed.contains(siteId)) {
                throw new ApiException(ErrorCode.SITE_FORBIDDEN);
            }
        }
        return plan;
    }
}
