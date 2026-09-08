package com.chh.autosense.common;

import com.chh.autosense.exception.ErrorCode;
import lombok.Data;

import java.io.Serializable;

/**
 * 通用响应包装:code 为内部错误码(0 表示成功,见 ErrorCode),data 为业务数据,
 * message 为提示信息。
 */
@Data
public class BaseResponse<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 内部错误码,0 表示成功
     */
    private int code;

    /**
     * 业务数据
     */
    private T data;

    /**
     * 提示信息
     */
    private String message;

    public BaseResponse(int code, T data, String message) {
        this.code = code;
        this.data = data;
        this.message = message;
    }

    public BaseResponse(int code, T data) {
        this(code, data, "");
    }

    public BaseResponse(ErrorCode errorCode) {
        this(errorCode.getCode(), null, errorCode.getMessage());
    }
}
