package com.snowworld.agent;

import com.snowworld.agent.AgentRunService.Created;
import com.snowworld.agent.AgentRunService.CreateRunRequest;
import com.snowworld.agent.AgentRunService.RunView;
import com.snowworld.agent.AgentRunService.StepView;
import com.snowworld.common.PageResult;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/agent")
@PreAuthorize("hasAuthority('AI_USE')")
public class AgentController implements DisposableBean {

    private static final List<String> TERMINAL = List.of("SUCCEEDED", "FAILED", "CANCELLED");
    private static final long SSE_TIMEOUT_MS = 170_000;

    private final AgentRunService service;
    private final AgentRunMapper mapper;
    private final ExecutorService sseExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public AgentController(AgentRunService service, AgentRunMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @PostMapping("/runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Created create(@RequestBody CreateRunRequest request) {
        return service.create(request);
    }

    @GetMapping("/runs")
    public PageResult<RunView> list(@RequestParam(required = false) String scenario,
                                    @RequestParam(required = false) Integer page,
                                    @RequestParam(required = false) Integer size) {
        return service.listMine(scenario, page, size);
    }

    @GetMapping("/runs/{id}")
    public RunView get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping("/runs/{id}/cancel")
    public RunView cancel(@PathVariable long id) {
        return service.cancel(id);
    }

    @GetMapping("/usage")
    public AgentRunService.Usage usage() {
        return service.usage();
    }

    /**
     * 任务进度事件流。事件: status(状态/进度变化)、step(新的工具调用摘要)、done(终态, 携带完整任务)。
     * 只发送进度、工具摘要与结果, 不包含密钥或模型内部推理; 连接断开后前端可改用 GET 轮询。
     */
    @GetMapping(value = "/runs/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable long id) {
        service.requireOwn(id);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        sseExecutor.submit(() -> stream(id, emitter));
        return emitter;
    }

    private void stream(long id, SseEmitter emitter) {
        long deadline = System.currentTimeMillis() + SSE_TIMEOUT_MS - 5_000;
        String lastStatus = null;
        String lastProgress = null;
        int lastSeq = 0;
        try {
            while (System.currentTimeMillis() < deadline) {
                AgentRunMapper.RunRow run = mapper.findById(id);
                if (run == null) {
                    break;
                }
                for (StepView step : service.steps(id, lastSeq)) {
                    lastSeq = step.seq();
                    emitter.send(SseEmitter.event().name("step").data(step, MediaType.APPLICATION_JSON));
                }
                if (!Objects.equals(run.getStatus(), lastStatus) || !Objects.equals(run.getProgress(), lastProgress)) {
                    lastStatus = run.getStatus();
                    lastProgress = run.getProgress();
                    emitter.send(SseEmitter.event().name("status").data(Map.of("status", lastStatus,
                            "progress", lastProgress == null ? "" : lastProgress, "toolCalls", run.getToolCalls(),
                            "modelCalls", run.getModelCalls()), MediaType.APPLICATION_JSON));
                }
                if (TERMINAL.contains(run.getStatus())) {
                    emitter.send(SseEmitter.event().name("done").data(service.view(run, true), MediaType.APPLICATION_JSON));
                    break;
                }
                Thread.sleep(1000);
            }
            emitter.complete();
        } catch (IOException | IllegalStateException e) {
            // 客户端已断开
            emitter.completeWithError(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            emitter.complete();
        } catch (RuntimeException e) {
            emitter.completeWithError(e);
        }
    }

    @Override
    public void destroy() {
        sseExecutor.shutdownNow();
    }
}
