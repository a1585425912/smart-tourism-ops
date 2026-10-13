package com.snowworld.review;

import com.snowworld.agent.AgentRunService;
import com.snowworld.common.ApiException;
import com.snowworld.common.ErrorCode;
import com.snowworld.common.PageResult;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
public class ReviewController {

    private final ReviewService service;
    private final AgentRunService agentRuns;

    public ReviewController(ReviewService service, AgentRunService agentRuns) {
        this.service = service;
        this.agentRuns = agentRuns;
    }

    @GetMapping("/reviews/topics")
    @PreAuthorize("hasAuthority('REVIEW_READ')")
    public List<ReviewDtos.TopicItem> topics() {
        return service.topics();
    }

    @GetMapping("/reviews")
    @PreAuthorize("hasAuthority('REVIEW_READ')")
    public PageResult<ReviewDtos.ReviewView> list(
            @RequestParam(required = false) List<Long> siteId,
            @RequestParam(required = false) String sentiment,
            @RequestParam(required = false) String topic,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Boolean complaint,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime end,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return service.page(siteId, sentiment, topic, keyword, status, complaint, start, end, page, size);
    }

    @GetMapping("/reviews/summary")
    @PreAuthorize("hasAuthority('REVIEW_READ')")
    public ReviewDtos.ReviewSummary summary(
            @RequestParam(required = false) Long siteId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime end) {
        return service.summary(siteId, start, end);
    }

    @GetMapping("/reviews/{id}")
    @PreAuthorize("hasAuthority('REVIEW_READ')")
    public ReviewDtos.ReviewDetail detail(@PathVariable long id) {
        return service.detail(id);
    }

    @PatchMapping("/reviews/{id}/classification")
    @PreAuthorize("hasAuthority('REVIEW_WRITE')")
    public ReviewDtos.ReviewDetail classify(@PathVariable long id, @RequestBody ReviewDtos.ClassificationRequest request) {
        return service.classify(id, request);
    }

    @PostMapping("/imports/reviews")
    @PreAuthorize("hasAuthority('REVIEW_IMPORT')")
    public ReviewDtos.ImportResult importCsv(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw new ApiException(ErrorCode.IMPORT_FAILED, "文件为空");
        }
        try {
            return service.importCsv(file.getBytes());
        } catch (IOException e) {
            throw new ApiException(ErrorCode.IMPORT_FAILED, "读取上传文件失败");
        }
    }

    /** 批量分析: 创建 REVIEW_ANALYSIS 场景的 Agent 任务。 */
    @PostMapping("/reviews/analysis-jobs")
    @PreAuthorize("hasAuthority('REVIEW_WRITE')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> analyze(@RequestBody(required = false) ReviewDtos.AnalysisJobRequest request) {
        ReviewDtos.AnalysisJobRequest r = request == null ? new ReviewDtos.AnalysisJobRequest(null, null, null) : request;
        AgentRunService.Created created = agentRuns.create(new AgentRunService.CreateRunRequest("REVIEW_ANALYSIS", null, null,
                r.siteId(), null, null, null, r.reviewIds(), r.limit(), null));
        return Map.of("runId", created.runId(), "status", created.status());
    }
}
