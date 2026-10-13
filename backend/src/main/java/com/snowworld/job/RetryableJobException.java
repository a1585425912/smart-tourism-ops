package com.snowworld.job;

/** 可重试的临时性失败(如外部服务 429/5xx、网络超时)。 */
public class RetryableJobException extends RuntimeException {

    public RetryableJobException(String message, Throwable cause) {
        super(message, cause);
    }

    public RetryableJobException(String message) {
        super(message);
    }
}
