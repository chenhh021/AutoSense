package com.chh.autosense.domain.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对话消息(data-model.md §7)。
 */
@Data
@Table("chat_message")
public class ChatMessage {
    @Id(keyType = KeyType.Auto)
    private Long id;
    private Long sessionId;
    private String role;
    private String content;
    private LocalDateTime createdAt;
}
