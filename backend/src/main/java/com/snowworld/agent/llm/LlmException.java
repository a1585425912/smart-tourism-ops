package com.snowworld.agent.llm;

/**
 * 模型调用失败。retryable=true 表示临时性错误(限流、5xx、超时、网络), 可有限退避重试;
 * 鉴权失败、余额不足、请求非法等为 false, 不应盲目重试。
 */
public class LlmException extends RuntimeException {

    private final boolean retryable;
    private final String code;

    public LlmException(String code, String message, boolean retryable) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }

    public LlmException(String code, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public String getCode() {
        return code;
    }
}
