package com.chh.autosense.common;

import com.chh.autosense.exception.ApiException;
import com.chh.autosense.exception.ErrorCode;
import com.chh.autosense.utils.LogSanitizer;
import com.chh.autosense.utils.ResultUtils;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理(T010):统一错误输出为 BaseResponse&lt;ErrorResponse&gt;,外层 code 为内部
 * 错误码,data 携带稳定错误语义(错误码名、可读消息、关联会话)。
 * HTTP 状态仍按错误码设置(response.setStatus),仅去掉 ResponseEntity 包装。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public BaseResponse<ErrorResponse> handleApi(ApiException e, HttpServletResponse response) {
        if (e.errorCode().httpStatus().is5xxServerError()) {
            log.error("Request failed: errorCode={}", e.errorCode(), LogSanitizer.diagnostic(e));
        } else {
            log.warn("Request rejected: errorCode={}", e.errorCode());
        }
        response.setStatus(e.errorCode().httpStatus().value());
        return ResultUtils.error(e.errorCode(),
                new ErrorResponse(e.errorCode().name(), e.getMessage(), e.sessionId()),
                e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public BaseResponse<ErrorResponse> handleValidation(MethodArgumentNotValidException e,
                                                        HttpServletResponse response) {
        e.getBindingResult().getFieldErrors().stream().limit(8).forEach(field ->
                log.warn("Request validation failed: field={}, rule={}",
                        LogSanitizer.label(field.getField()), LogSanitizer.label(field.getCode())));
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .orElse("请求参数不合法");
        return badRequest(message, response);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public BaseResponse<ErrorResponse> handleUnreadable(HttpMessageNotReadableException e,
                                                        HttpServletResponse response) {
        log.warn("Request validation failed: errorCode=BAD_REQUEST, reasonCode=UNREADABLE_BODY");
        return badRequest("请求体格式不合法", response);
    }

    @ExceptionHandler(Exception.class)
    public BaseResponse<ErrorResponse> handleUnexpected(Exception e, HttpServletResponse response) {
        log.error("Request failed: errorCode=INTERNAL_ERROR", LogSanitizer.diagnostic(e));
        response.setStatus(ErrorCode.INTERNAL_ERROR.httpStatus().value());
        return ResultUtils.error(ErrorCode.INTERNAL_ERROR,
                new ErrorResponse(ErrorCode.INTERNAL_ERROR.name(), "服务内部错误", null),
                "服务内部错误");
    }

    private static BaseResponse<ErrorResponse> badRequest(String message, HttpServletResponse response) {
        response.setStatus(ErrorCode.BAD_REQUEST.httpStatus().value());
        return ResultUtils.error(ErrorCode.BAD_REQUEST,
                new ErrorResponse(ErrorCode.BAD_REQUEST.name(), message, null), message);
    }
}
