package com.snowworld.job;

/** 任务执行上下文: 续租与取消检查。长任务应在每个步骤之间调用 {@link #checkpoint()}。 */
public class JobContext {

    /** 任务被取消时抛出, 由 worker 标记为 CANCELLED。 */
    public static class CancelledException extends RuntimeException {
        public CancelledException() {
            super("任务已取消");
        }
    }

    private final long jobId;
    private final String owner;
    private final JobMapper mapper;
    private final int leaseSeconds;

    JobContext(long jobId, String owner, JobMapper mapper, int leaseSeconds) {
        this.jobId = jobId;
        this.owner = owner;
        this.mapper = mapper;
        this.leaseSeconds = leaseSeconds;
    }

    public long jobId() {
        return jobId;
    }

    /** 续租并检查取消标记。租约已被其它 worker 接管时同样中止, 避免并发重复执行。 */
    public void checkpoint() {
        if (mapper.heartbeat(jobId, owner, leaseSeconds) == 0) {
            throw new CancelledException();
        }
        if (mapper.isCancelRequested(jobId)) {
            throw new CancelledException();
        }
    }

    public boolean isCancelRequested() {
        return mapper.isCancelRequested(jobId);
    }
}
