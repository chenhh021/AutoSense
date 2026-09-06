package com.chh.autosense.domain.dto;

import com.chh.autosense.domain.entity.User;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 用户视图(FR-024):脱敏输出,永不含 userPassword(R20)。
 */
@Schema(description = "脱敏用户信息，不包含密码")
public record UserView(
        @Schema(description = "用户唯一标识", example = "12")
        Long id,
        @Schema(description = "用户登录账号", example = "zhangsan")
        String userAccount,
        @Schema(description = "用户昵称", example = "张三")
        String userName,
        @Schema(description = "用户头像地址", example = "https://example.com/avatar/12.png")
        String userAvatar,
        @Schema(description = "用户简介", example = "智能家居爱好者")
        String userProfile,
        @Schema(description = "用户角色", example = "user", allowableValues = {"user", "admin"})
        String userRole
) {
    public static UserView of(User u) {
        return new UserView(u.getId(), u.getUserAccount(), u.getUserName(),
                u.getUserAvatar(), u.getUserProfile(), u.getUserRole());
    }
}
