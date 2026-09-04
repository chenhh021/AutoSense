package com.chh.autosense.api;

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
        return ResponseEntity.status(e.errorCode().httpStatus())
                .body(new ErrorResponse(e.errorCode().name(), e.getMessage(), e.sessionId()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .orElse("请求参数不合法");
        return ResponseEntity.status(ErrorCode.BAD_REQUEST.httpStatus())
                .body(new ErrorResponse(ErrorCode.BAD_REQUEST.name(), message, null));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.status(ErrorCode.BAD_REQUEST.httpStatus())
                .body(new ErrorResponse(ErrorCode.BAD_REQUEST.name(), "请求体格式不合法", null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("未预期错误", e);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.httpStatus())
                .body(new ErrorResponse(ErrorCode.INTERNAL_ERROR.name(), "服务内部错误", null));
    }
}
