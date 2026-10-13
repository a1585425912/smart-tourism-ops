package com.snowworld.job;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 数据库任务表驱动的后台 worker。
 *
 * <ul>
 *   <li>租约: 领取时写入 lease_until, 执行中由 {@link JobContext#checkpoint()} 续租;</li>
 *   <li>恢复: 进程崩溃后租约过期的 RUNNING 任务会被重新领取;</li>
 *   <li>重试: {@link RetryableJobException} 按指数退避重试, 超过最大次数判定失败;</li>
 *   <li>取消: QUEUED 直接取消, RUNNING 在下一个检查点中止。</li>
 * </ul>
 */
@Component
public class JobWorker implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);
    private static final int LEASE_SECONDS = 90;

    private final JobMapper mapper;
    private final Map<String, JobHandler> handlers;
    private final String owner = ManagementFactory.getRuntimeMXBean().getName() + "-" + UUID.randomUUID().toString().substring(0, 8);
    private final ExecutorService executor;
    private final Semaphore slots;
    private final boolean enabled;

    public JobWorker(JobMapper mapper, List<JobHandler> handlerList,
                     @Value("${snow.job.concurrency:4}") int concurrency,
                     @Value("${snow.job.enabled:true}") boolean enabled) {
        this.mapper = mapper;
        this.handlers = handlerList.stream().collect(Collectors.toMap(JobHandler::type, Function.identity()));
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.slots = new Semaphore(concurrency);
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${snow.job.poll-ms:1000}", initialDelay = 3000)
    public void poll() {
        if (!enabled) {
            return;
        }
        mapper.failExhausted();
        mapper.cancelAbandoned();
        while (slots.tryAcquire()) {
            JobMapper.JobRow job;
            try {
                job = mapper.claim(owner, LEASE_SECONDS);
            } catch (RuntimeException e) {
                slots.release();
                log.warn("job claim failed: {}", e.getMessage());
                return;
            }
            if (job == null) {
                slots.release();
                return;
            }
            executor.submit(() -> {
                try {
                    run(job);
                } finally {
                    slots.release();
                }
            });
        }
    }

    void run(JobMapper.JobRow job) {
        JobHandler handler = handlers.get(job.getJobType());
        if (handler == null) {
            mapper.release(job.getId(), owner, "FAILED", "没有对应的任务处理器: " + job.getJobType(), 0);
            return;
        }
        JobContext ctx = new JobContext(job.getId(), owner, mapper, LEASE_SECONDS);
        try {
            handler.handle(job, ctx);
            mapper.succeed(job.getId(), owner);
        } catch (JobContext.CancelledException e) {
            mapper.release(job.getId(), owner, "CANCELLED", "任务已取消", 0);
        } catch (RetryableJobException e) {
            boolean exhausted = job.getAttempts() >= job.getMaxAttempts();
            int delay = (int) Math.min(300, 5 * Math.pow(3, job.getAttempts() - 1));
            log.warn("job {} ({}) attempt {} failed: {}", job.getId(), job.getJobType(), job.getAttempts(), e.getMessage());
            mapper.release(job.getId(), owner, exhausted ? "FAILED" : "QUEUED", truncate(e.getMessage()), exhausted ? 0 : delay);
        } catch (Exception e) {
            log.error("job {} ({}) failed", job.getId(), job.getJobType(), e);
            mapper.release(job.getId(), owner, "FAILED", truncate(e.getClass().getSimpleName() + ": " + e.getMessage()), 0);
        }
    }

    private static String truncate(String s) {
        return s == null ? null : s.substring(0, Math.min(s.length(), 1000));
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
