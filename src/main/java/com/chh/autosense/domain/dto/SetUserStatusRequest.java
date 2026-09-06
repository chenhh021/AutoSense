package com.chh.autosense.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * 禁用/启用请求(FR-027):disabled=true 禁用并踢下线;false 恢复。
 */
@Schema(description = "用户禁用或启用请求")
public record SetUserStatusRequest(
        @Schema(description = "目标状态：true 表示禁用并使全部令牌失效，false 表示启用",
                example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "disabled 不能为空") Boolean disabled
) {
}
