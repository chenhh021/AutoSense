package com.chh.autosense.domain.dto;

import com.chh.autosense.service.user.UserService;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 管理端用户分页列表(FR-027)。
 */
@Schema(description = "管理端用户分页查询响应")
public record AdminUserPageView(
        @Schema(description = "符合查询条件的用户总数", example = "57")
        long total,
        @Schema(description = "当前页码", example = "1")
        int page,
        @Schema(description = "当前每页记录数", example = "20")
        int size,
        @Schema(description = "当前页的脱敏用户列表")
        List<UserView> records
) {
    public static AdminUserPageView of(UserService.PageResult result) {
        return new AdminUserPageView(result.total(), result.page(), result.size(),
                result.records().stream().map(UserView::of).toList());
    }
}
