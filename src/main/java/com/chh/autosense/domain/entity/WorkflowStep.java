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
@Table("workflow_step")
public class WorkflowStep {
    @Id
    private String requestId;
    @Id
    private String stepId;
    private Integer ordinal;
    private String type;
    private String status;
    private String inputJson;
    private String inputHash;
    private String resultJson;
    private String failureCode;
    private String certainty;
    private Integer retriesUsed;
    private Integer maxRetries;
    private String activeAttemptId;
    private String commandExecutionId;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime updatedAt;
}

