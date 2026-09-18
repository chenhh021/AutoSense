package com.chh.autosense.domain.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@Table("command_execution")
public class CommandExecution {
    @Id
    private String commandId;
    private String requestId;
    private String stepId;
    private String operationKey;
    private Long sessionId;
    private Long userId;
    private Long deviceId;
    private String actionCode;
    private String canonicalParams;
    private String paramsHash;
    private String approvalId;
    private String scopeHash;
    private String status;
    private String certainty;
    private String resultJson;
    private String failureCode;
    private String activeAttemptId;
    private Integer attemptCount;
    private Integer retriesUsed;
    private Integer maxRetries;
    private Long version;
    private Long fence;
    private String remoteOperationId;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime updatedAt;
}

