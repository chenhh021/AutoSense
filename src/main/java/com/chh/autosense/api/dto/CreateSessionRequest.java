package com.chh.autosense.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 发起诊断会话请求(contracts §1)。
 */
public record CreateSessionRequest(@NotBlank String problem) {
}
