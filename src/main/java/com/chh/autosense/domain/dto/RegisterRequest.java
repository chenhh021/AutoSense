package com.chh.autosense.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * 注册请求(FR-022)。不含 role 字段——夹带即忽略,防提权(R23)。
 */
@Schema(description = "用户注册请求")
public record RegisterRequest(
        @Schema(description = "登录账号，须为 4~32 位字母、数字或下划线",
                example = "zhangsan", minLength = 4, maxLength = 32,
                pattern = "^[a-zA-Z0-9_]{4,32}$", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "账号不能为空") String userAccount,
        @Schema(description = "登录密码，须为 8~64 位且同时包含字母和数字",
                example = "pass1234", format = "password", minLength = 8, maxLength = 64,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "密码不能为空") String userPassword,
        @Schema(description = "确认密码，必须与登录密码一致",
                example = "pass1234", format = "password", minLength = 8, maxLength = 64,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "确认密码不能为空") String confirmPassword
) {
}
