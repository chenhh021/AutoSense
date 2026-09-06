package com.chh.autosense.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 设备登记请求(FR-020,contracts §6)。
 */
public record RegisterDeviceRequest(
        @NotBlank(message = "SN 不能为空")
        @Pattern(regexp = "^[A-Z0-9]{4}[0-9]{9}$", message = "SN 格式不合法")
        String sn,
        @NotBlank(message = "设备名称不能为空")
        @Size(max = 64, message = "设备名称不能超过 64 个字符")
        String name
) {
}
