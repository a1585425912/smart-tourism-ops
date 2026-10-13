package com.snowworld.job;

import com.snowworld.common.ApiException;
import com.snowworld.common.ClockConfig;
import com.snowworld.common.SnowProperties;
import java.time.Clock;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class JobService {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JobMapper mapper;
    private final List<JobHandler> handlers;

    public JobService(JobMapper mapper, List<JobHandler> handlers) {
        this.mapper = mapper;
        this.handlers = handlers;
    }

    /**
     * 幂等入队。idempotencyKey 相同的任务只会创建一次, 重复调用返回已有任务。
     *
     * @param payload 任意可序列化对象, 存为 JSONB
     */
    public JobMapper.JobRow enqueue(String type, String idempotencyKey, Long refId, Object payload, Long createdBy) {
        JobMapper.JobRow row = new JobMapper.JobRow();
        row.setJobType(type);
        row.setIdempotencyKey(idempotencyKey);
        row.setRefId(refId);
        row.setPayload(payload == null ? JSON.createObjectNode() : JSON.valueToTree(payload));
        row.setMaxAttempts(handlers.stream().filter(h -> h.type().equals(type)).findFirst()
                .map(JobHandler::maxAttempts).orElse(3));
        row.setCreatedBy(createdBy);
        if (mapper.insert(row) == 0 && idempotencyKey != null) {
            return mapper.findByKey(idempotencyKey);
        }
        return mapper.findById(row.getId());
    }

    public boolean cancel(long jobId) {
        return mapper.requestCancel(jobId) > 0;
    }

    public JobMapper.JobRow get(long jobId) {
        return mapper.findById(jobId);
    }

    public static <T> T payload(JobMapper.JobRow job, Class<T> type) {
        return JSON.treeToValue(job.getPayload(), type);
    }

    public static JsonNode tree(Object value) {
        return JSON.valueToTree(value);
    }

    /** 任务状态查看与失败重试(接入与任务状态页)。 */
    @RestController
    public static class JobController {
        private final JobMapper mapper;
        private final Clock clock;
        private final SnowProperties props;

        public JobController(JobMapper mapper, Clock clock, SnowProperties props) {
            this.mapper = mapper;
            this.clock = clock;
            this.props = props;
        }

        @GetMapping("/api/v1/jobs")
        @PreAuthorize("hasAuthority('INTEGRATION_READ')")
        public List<JobMapper.JobRow> list(@RequestParam(required = false) String type,
                                           @RequestParam(required = false) String status,
                                           @RequestParam(defaultValue = "50") int limit) {
            return mapper.list(type, status, Math.min(Math.max(limit, 1), 200)).stream().map(this::view).toList();
        }

        @PostMapping("/api/v1/jobs/{id}/retry")
        @PreAuthorize("hasAuthority('INTEGRATION_MANAGE')")
        public JobMapper.JobRow retry(@PathVariable long id) {
            if (mapper.retry(id) == 0) {
                throw ApiException.badRequest("只有失败的任务可以重试");
            }
            return view(mapper.findById(id));
        }

        private JobMapper.JobRow view(JobMapper.JobRow row) {
            if (row == null || props.demo().replayAt() == null) return row;
            row.setCreatedAt(ClockConfig.executionTime(row.getCreatedAt(), clock, true));
            row.setUpdatedAt(ClockConfig.executionTime(row.getUpdatedAt(), clock, true));
            row.setFinishedAt(ClockConfig.executionTime(row.getFinishedAt(), clock, true));
            row.setHeartbeatAt(ClockConfig.executionTime(row.getHeartbeatAt(), clock, true));
            row.setLeaseUntil(ClockConfig.executionTime(row.getLeaseUntil(), clock, true));
            row.setRunAfter(ClockConfig.executionTime(row.getRunAfter(), clock, true));
            return row;
        }
    }
}
