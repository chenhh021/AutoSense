package com.chh.autosense.api.dto;

import java.time.LocalDateTime;

/**
 * 对话消息视图(contracts §4)。
 */
public record ChatMessageView(String role, String content, LocalDateTime createdAt) {
}
