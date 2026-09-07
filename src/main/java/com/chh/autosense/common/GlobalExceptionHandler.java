package com.chh.autosense.common;

import com.chh.autosense.exception.ApiException;
import com.chh.autosense.exception.ErrorCode;
import com.chh.autosense.utils.LogSanitizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理(T010):统一错误体 {code, message, sessionId}。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException e) {
        if (e.errorCode().httpStatus().is5xxServerError()) {
            log.error("Request failed: errorCode={}", e.errorCode(), LogSanitizer.diagnostic(e));
        } else {
            log.warn("Request rejected: errorCode={}", e.errorCode());
        }
        return ResponseEntity.status(e.errorCode().httpStatus())
                .body(new ErrorResponse(e.errorCode().name(), e.getMessage(), e.sessionId()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        e.getBindingResult().getFieldErrors().stream().limit(8).forEach(field ->
                log.warn("Request validation failed: field={}, rule={}",
                        LogSanitizer.label(field.getField()), LogSanitizer.label(field.getCode())));
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .orElse("请求参数不合法");
        return ResponseEntity.status(ErrorCode.BAD_REQUEST.httpStatus())
                .body(new ErrorResponse(ErrorCode.BAD_REQUEST.name(), message, null));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException e) {
        log.warn("Request validation failed: errorCode=BAD_REQUEST, reasonCode=UNREADABLE_BODY");
        return ResponseEntity.status(ErrorCode.BAD_REQUEST.httpStatus())
                .body(new ErrorResponse(ErrorCode.BAD_REQUEST.name(), "请求体格式不合法", null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("Request failed: errorCode=INTERNAL_ERROR", LogSanitizer.diagnostic(e));
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.httpStatus())
                .body(new ErrorResponse(ErrorCode.INTERNAL_ERROR.name(), "服务内部错误", null));
    }
}
