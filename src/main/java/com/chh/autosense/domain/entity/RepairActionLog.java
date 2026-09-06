package com.chh.autosense.domain.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 修复动作/状态迁移日志(data-model.md §5,FR-013 追溯依据)。
 */
@Data
@Table("repair_action_log")
public class RepairActionLog {
    @Id(keyType = KeyType.Auto)
    private Long id;
    private Long sessionId;
    private String actionCode;
    private String params;
    private String result;
    private String message;
    private LocalDateTime createdAt;
}
