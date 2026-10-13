package com.snowworld.report;

import com.snowworld.agent.AgentRunService;
import com.snowworld.auth.ScopeService;
import com.snowworld.common.ApiException;
import com.snowworld.common.PageResult;
import java.util.List;
import java.util.Map;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 运营日报: 每次生成建立新版本, 历史版本及其统计快照保持不变。 */
@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    public record GenerateRequest(Long siteId, String reportDate) {
    }

    private final ReportMapper mapper;
    private final ScopeService scope;
    private final AgentRunService agentRuns;

    public ReportController(ReportMapper mapper, ScopeService scope, AgentRunService agentRuns) {
        this.mapper = mapper;
        this.scope = scope;
        this.agentRuns = agentRuns;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('REPORT_READ')")
    public PageResult<ReportMapper.ReportRow> list(@RequestParam(required = false) List<Long> siteId,
                                                   @RequestParam(required = false) Integer page,
                                                   @RequestParam(required = false) Integer size,
                                                   @RequestParam(defaultValue = "false") boolean latestOnly,
                                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate reportDate) {
        List<Long> sites = scope.resolve(siteId);
        int p = PageResult.clampPage(page);
        int s = PageResult.clampSize(size);
        if (sites.isEmpty()) {
            return new PageResult<>(List.of(), 0, p, s);
        }
        return new PageResult<>(mapper.list(sites, s, PageResult.offset(p, s), latestOnly, reportDate), mapper.count(sites, latestOnly, reportDate), p, s);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('REPORT_READ')")
    public ReportMapper.ReportRow detail(@PathVariable long id) {
        ReportMapper.ReportRow row = mapper.findById(id);
        if (row == null) {
            throw ApiException.notFound("报告");
        }
        scope.requireSite(row.getSiteId());
        return row;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('REPORT_WRITE')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> generate(@RequestBody GenerateRequest request) {
        AgentRunService.Created created = agentRuns.create(new AgentRunService.CreateRunRequest("DAILY_REPORT", null, null,
                request.siteId(), request.reportDate(), null, null, null, null, null));
        return Map.of("runId", created.runId(), "status", created.status());
    }
}
