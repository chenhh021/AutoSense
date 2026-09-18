package com.chh.autosense.domain.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 修复动作/状态迁移日志(data-model.md §5,FR-013 追溯依据)。
 */
@Getter
@Setter
@NoArgsConstructor
@Table("repair_action_log")
public class RepairActionLog {
    @Id(keyType = KeyType.Auto)
    private Long id;
    private Long sessionId;
    private String actionCode;
    private String params;
    private String result;
    private String message;
    private String requestId;
    private String stepId;
    private String commandExecutionId;
    private String attemptId;
    private Long actorUserId;
    private String eventType;
    private String operationKind;
    private Long eventSequence;
    private String eventKey;
    private String resultCode;
    private Long chatMessageRef;
    private Integer schemaVersion;
    private LocalDateTime createdAt;
}
