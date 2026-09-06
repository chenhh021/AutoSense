package com.chh.autosense.exception;

/**
 * 业务异常:携带错误码与用户可读信息,由 GlobalExceptionHandler 统一转响应。
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final Long sessionId;

    public ApiException(ErrorCode code, String message) {
        this(code, message, null);
    }

    public ApiException(ErrorCode code, String message, Long sessionId) {
        super(message);
        this.code = code;
        this.sessionId = sessionId;
    }

    public ErrorCode errorCode() {
        return code;
    }

    public Long sessionId() {
        return sessionId;
    }
}
