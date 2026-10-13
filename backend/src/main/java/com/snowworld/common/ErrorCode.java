package com.snowworld.common;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "请求参数不合法"),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "参数校验失败"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "未登录或会话已过期"),
    BAD_CREDENTIALS(HttpStatus.UNAUTHORIZED, "用户名或密码错误"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "无权执行该操作"),
    SITE_FORBIDDEN(HttpStatus.FORBIDDEN, "无权访问该景区数据"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "资源不存在"),
    CONFLICT(HttpStatus.CONFLICT, "数据已被修改，请刷新后重试"),
    ILLEGAL_TRANSITION(HttpStatus.CONFLICT, "当前状态不允许该操作"),
    INVALID_GEOMETRY(HttpStatus.UNPROCESSABLE_ENTITY, "空间数据不合法"),
    IMPORT_FAILED(HttpStatus.UNPROCESSABLE_ENTITY, "导入数据存在错误"),
    RANGE_TOO_LARGE(HttpStatus.BAD_REQUEST, "查询时间范围或粒度超出上限"),
    QUOTA_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "AI 调用额度已用完"),
    AI_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "AI 服务暂不可用"),
    VIDEO_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "视频服务不可用"),
    PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "上传文件过大"),
    INTERNAL(HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
