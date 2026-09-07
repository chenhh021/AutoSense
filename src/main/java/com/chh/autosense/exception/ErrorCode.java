package com.chh.autosense.exception;

import org.springframework.http.HttpStatus;

/**
 * API 错误码(contracts/diagnosis-api.md 通用约定)。
 */
public enum ErrorCode {
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED),
    DEVICE_FORBIDDEN(HttpStatus.FORBIDDEN),
    SESSION_NOT_FOUND(HttpStatus.NOT_FOUND),
    DEVICE_NOT_FOUND(HttpStatus.NOT_FOUND),
    DEVICE_BUSY(HttpStatus.CONFLICT),
    SESSION_BUSY(HttpStatus.CONFLICT),
    AI_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    CAPABILITY_NOT_AVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    REQUEST_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT),
    CONTEXT_EXPIRED(HttpStatus.CONFLICT),
    DEVICE_ALREADY_BOUND(HttpStatus.CONFLICT),
    DEVICE_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    UNSUPPORTED_DEVICE_TYPE(HttpStatus.UNPROCESSABLE_ENTITY),
    DEVICE_UNREACHABLE(HttpStatus.UNPROCESSABLE_ENTITY),
    BAD_REQUEST(HttpStatus.BAD_REQUEST),
    /** 非管理员访问管理端点(FR-027,2026-08-29) */
    FORBIDDEN(HttpStatus.FORBIDDEN),
    /** 管理端点目标用户不存在(FR-027) */
    USER_NOT_FOUND(HttpStatus.NOT_FOUND),
    /** 注册账号已存在(FR-022) */
    ACCOUNT_EXISTS(HttpStatus.CONFLICT),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus httpStatus;

    ErrorCode(HttpStatus httpStatus) {
        this.httpStatus = httpStatus;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }
}
