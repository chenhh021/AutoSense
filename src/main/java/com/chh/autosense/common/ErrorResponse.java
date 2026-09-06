package com.chh.autosense.common;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 统一错误响应体(contracts/diagnosis-api.md 通用约定)。
 */
@Schema(description = "统一错误响应")
public record ErrorResponse(
        @Schema(description = "业务错误码", example = "BAD_REQUEST")
        String code,
        @Schema(description = "便于阅读的错误原因", example = "请求参数不合法")
        String message,
        @Schema(description = "关联的诊断会话 ID；用户账号接口通常为空",
                example = "10001", nullable = true)
        Long sessionId
) {
}
