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
@Table("workflow_approval")
public class WorkflowApproval {
    @Id
    private String approvalId;
    private String requestId;
    private String stepId;
    private Long userId;
    private String scopeHash;
    private String operationKind;
    private String deviceRefs;
    private String status;
    private LocalDateTime expiresAt;
    private LocalDateTime decisionAt;
    private Long version;
    private LocalDateTime createdAt;
}

