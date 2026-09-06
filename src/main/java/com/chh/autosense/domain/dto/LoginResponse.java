package com.chh.autosense.domain.dto;

import com.chh.autosense.domain.entity.User;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 登录响应(FR-023):Bearer 令牌 + 当前用户信息。
 */
@Schema(description = "用户登录成功响应")
public record LoginResponse(
        @Schema(description = "访问令牌，在需要认证的接口中通过 Authorization 请求头传递",
                example = "Bx8J5mK2pQ7wV4yN9cR6tL1sH3dF0aZg")
        String token,
        @Schema(description = "令牌类型，固定为 Bearer", example = "Bearer")
        String tokenType,
        @Schema(description = "当前登录用户的脱敏信息")
        UserView user
) {
    public static LoginResponse of(String token, User user) {
        return new LoginResponse(token, "Bearer", UserView.of(user));
    }
}
