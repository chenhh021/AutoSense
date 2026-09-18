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
@Table("workflow_execution")
public class WorkflowExecution {
    @Id
    private String requestId;
    private Long userId;
    private Long sessionId;
    private Long reportId;
    private Long originMessageId;
    private Long latestInputMessageId;
    private String graphVersion;
    private Integer schemaVersion;
    private String status;
    private String suspendedStatus;
    private Integer currentStepIndex;
    private String planJson;
    private String failureCode;
    private String inputRequestId;
    private String prompt;
    private Long version;
    private String leaseOwner;
    private Long fence;
    private LocalDateTime leaseUntil;
    private Long lastEventSequence;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime finishedAt;
}

