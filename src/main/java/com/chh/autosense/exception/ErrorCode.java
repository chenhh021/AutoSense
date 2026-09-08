package com.chh.autosense.exception;

import org.springframework.http.HttpStatus;

/**
 * API 错误码(contracts/diagnosis-api.md 通用约定)。
 * code 为内部错误码:OK 固定为 0,其余为五位数字、从 40000 开始,相似错误在数字上尽量靠近;
 * message 为默认提示,抛出处可携带更具体的消息覆盖。
 */
public enum ErrorCode {
    OK(0, "ok"),
    BAD_REQUEST(40000, "Bad request"),
    UNAUTHORIZED(40001, "Request is unauthorized"),
    /** 非管理员访问管理端点(FR-027,2026-08-29) */
    FORBIDDEN(40002, "Forbidden"),
    DEVICE_FORBIDDEN(40003, "Forbidden"),
    /** 管理端点目标用户不存在(FR-027) */
    USER_NOT_FOUND(40004, "User not found"),
    SESSION_NOT_FOUND(40005, "Session not found"),
    DEVICE_NOT_FOUND(40006, "Device not found"),
    /** 注册账号已存在(FR-022) */
    ACCOUNT_EXISTS(40007, "Account already exists"),
    DEVICE_ALREADY_BOUND(40008, "Device already bound"),
    DEVICE_BUSY(40009, "Device is busy"),
    SESSION_BUSY(40010, "Session is busy"),
    CONTEXT_EXPIRED(40011, "Context expired"),
    UNSUPPORTED_DEVICE_TYPE(40012, "Unsupported device type"),
    DEVICE_UNREACHABLE(40013, "Device unreachable"),
    REQUEST_TIMEOUT(40014, "Request timeout"),
    AI_SERVICE_UNAVAILABLE(40015, "AI service unavailable"),
    CAPABILITY_NOT_AVAILABLE(40016, "Capability not available"),
    DEVICE_SERVICE_UNAVAILABLE(40017, "Device service unavailable"),
    INTERNAL_ERROR(50000, "Internal error");

    /**
     * 状态码
     */
    private final int code;

    /**
     * 信息
     */
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    /**
     * 该错误对应的 HTTP 响应状态
     *
     * @return 对应的 HttpStatus
     */
    public HttpStatus httpStatus() {
        return switch (this) {
            case OK -> HttpStatus.OK;
            case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN, DEVICE_FORBIDDEN -> HttpStatus.FORBIDDEN;
            case USER_NOT_FOUND, SESSION_NOT_FOUND, DEVICE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case ACCOUNT_EXISTS, DEVICE_ALREADY_BOUND, DEVICE_BUSY, SESSION_BUSY, CONTEXT_EXPIRED ->
                    HttpStatus.CONFLICT;
            case UNSUPPORTED_DEVICE_TYPE, DEVICE_UNREACHABLE -> HttpStatus.UNPROCESSABLE_ENTITY;
            case REQUEST_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case AI_SERVICE_UNAVAILABLE, CAPABILITY_NOT_AVAILABLE, DEVICE_SERVICE_UNAVAILABLE ->
                    HttpStatus.SERVICE_UNAVAILABLE;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
