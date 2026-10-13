package com.snowworld.inspection;

import com.snowworld.auth.ScopeService;
import com.snowworld.common.ClockConfig;
import com.snowworld.common.PageResult;
import com.snowworld.common.TimeRange;
import com.snowworld.inspection.InspectionDtos.Candidate;
import com.snowworld.inspection.InspectionDtos.CreateTask;
import com.snowworld.inspection.InspectionDtos.ResultsRequest;
import com.snowworld.inspection.InspectionDtos.TaskDetail;
import com.snowworld.inspection.InspectionDtos.TaskView;
import com.snowworld.inspection.InspectionDtos.TemplateView;
import com.snowworld.inspection.InspectionDtos.TransitionRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
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

@RestController
@RequestMapping("/api/v1")
public class InspectionController {

    private final InspectionService service;
    private final InspectionQueryPort queryPort;
    private final ScopeService scope;
    private final Clock clock;

    public InspectionController(InspectionService service, InspectionQueryPort queryPort, ScopeService scope, Clock clock) {
        this.service = service;
        this.queryPort = queryPort;
        this.scope = scope;
        this.clock = clock;
    }

    @GetMapping("/inspection-templates")
    @PreAuthorize("hasAuthority('INSPECTION_READ')")
    public List<TemplateView> templates() {
        return service.templates();
    }

    @GetMapping("/inspection-tasks")
    @PreAuthorize("hasAuthority('INSPECTION_READ')")
    public PageResult<TaskView> list(@RequestParam(required = false) List<Long> siteId,
                                     @RequestParam(required = false) List<String> status,
                                     @RequestParam(defaultValue = "false") boolean mine,
                                     @RequestParam(required = false) String keyword,
                                     @RequestParam(required = false) Integer page,
                                     @RequestParam(required = false) Integer size) {
        List<String> statuses = status == null ? null
                : status.stream().flatMap(s -> Arrays.stream(s.split(","))).map(String::strip)
                .filter(s -> !s.isEmpty()).toList();
        return service.list(siteId, statuses, mine, keyword, page, size);
    }

    @PostMapping("/inspection-tasks")
    @PreAuthorize("hasAuthority('INSPECTION_MANAGE')")
    @ResponseStatus(HttpStatus.CREATED)
    public TaskDetail create(@RequestBody CreateTask req) {
        return service.create(req);
    }

    @GetMapping("/inspection-tasks/{id}")
    @PreAuthorize("hasAuthority('INSPECTION_READ')")
    public TaskDetail detail(@PathVariable long id) {
        return service.detail(id);
    }

    @PostMapping("/inspection-tasks/{id}/transitions")
    @PreAuthorize("hasAuthority('INSPECTION_READ')")
    public TaskDetail transition(@PathVariable long id, @RequestBody TransitionRequest req) {
        return service.transition(id, req);
    }

    @PostMapping("/inspection-tasks/{id}/results")
    @PreAuthorize("hasAuthority('INSPECTION_EXECUTE')")
    public TaskDetail results(@PathVariable long id, @RequestBody ResultsRequest req) {
        return service.saveResults(id, req);
    }

    @GetMapping("/inspection/candidates")
    @PreAuthorize("hasAnyAuthority('INSPECTION_MANAGE', 'INSPECTION_REVIEW')")
    public List<Candidate> candidates(@RequestParam long siteId, @RequestParam(defaultValue = "INSPECTOR") String role) {
        return service.candidates(siteId, role);
    }

    /** 默认统计最近 30 天(按业务时钟)。 */
    @GetMapping("/inspection/stats")
    @PreAuthorize("hasAuthority('INSPECTION_READ')")
    public InspectionQueryPort.InspectionSummary stats(
            @RequestParam(required = false) List<Long> siteId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime end) {
        List<Long> sites = scope.resolve(siteId);
        OffsetDateTime e = end != null ? end
                : LocalDate.now(clock).plusDays(1).atStartOfDay(ClockConfig.BIZ_ZONE).toOffsetDateTime();
        OffsetDateTime s = start != null ? start : e.minusDays(30);
        return queryPort.summary(sites, new TimeRange(s, e).limit(Duration.ofDays(400)));
    }
}
