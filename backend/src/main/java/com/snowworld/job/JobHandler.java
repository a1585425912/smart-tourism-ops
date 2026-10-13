package com.snowworld.job;

/**
 * 后台任务处理器。实现类注册为 Spring Bean, 按 {@link #type()} 分派。
 *
 * <p>约定: 处理器必须可重入——任务可能因进程重启在另一次执行中被恢复, 业务写入需依赖幂等键或状态检查避免重复。
 */
public interface JobHandler {

    String type();

    /** 默认最多执行次数(含首次)。 */
    default int maxAttempts() {
        return 3;
    }

    /** 抛出 {@link RetryableJobException} 时按退避重试; 其它异常直接失败。 */
    void handle(JobMapper.JobRow job, JobContext context) throws Exception;
}
