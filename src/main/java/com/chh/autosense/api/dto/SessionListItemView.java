package com.chh.autosense.api.dto;

import java.time.LocalDateTime;

/**
 * 历史对话列表项(FR-018,contracts §5):preview 取会话首条用户问题/最近结论。
 */
public record SessionListItemView(
        Long sessionId,
        String status,
        String preview,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
