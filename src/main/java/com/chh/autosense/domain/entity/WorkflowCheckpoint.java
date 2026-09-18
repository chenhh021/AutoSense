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
@Table("workflow_checkpoint")
public class WorkflowCheckpoint {
    @Id
    private String threadId;
    @Id
    private String checkpointId;
    private String parentCheckpointId;
    private String nodeId;
    private String nextNode;
    private String statePayload;
    private Integer schemaVersion;
    private String graphVersion;
    private Long version;
    private Long fence;
    private LocalDateTime createdAt;
}

