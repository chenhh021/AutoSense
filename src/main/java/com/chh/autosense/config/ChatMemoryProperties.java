package com.chh.autosense.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LLM 端对话记忆窗口(FR-018,默认最近 20 条;超出部分长期留存于 MySQL)。
 */
@ConfigurationProperties(prefix = "autosense.chat-memory")
public record ChatMemoryProperties(Integer window) {
}
