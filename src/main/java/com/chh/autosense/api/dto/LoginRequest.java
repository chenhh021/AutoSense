package com.chh.autosense.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * 登录请求(FR-023)。
 */
@Schema(description = "用户登录请求")
public record LoginRequest(
        @Schema(description = "用户登录账号", example = "zhangsan",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "账号不能为空") String userAccount,
        @Schema(description = "用户登录密码", example = "pass1234", format = "password",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "密码不能为空") String userPassword
) {
}
