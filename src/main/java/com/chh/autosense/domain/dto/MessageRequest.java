package com.chh.autosense.domain.dto;

/**
 * 会话内追加消息(contracts §2):澄清回答 / 设备确认 / 修复确认 / 位置提供。
 */
public record MessageRequest(String content, Boolean confirmRepair) {
}
