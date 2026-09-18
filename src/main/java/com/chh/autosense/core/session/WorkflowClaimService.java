package com.chh.autosense.core.session;

import com.chh.autosense.config.GraphProperties;
import com.chh.autosense.domain.entity.WorkflowExecution;
import com.chh.autosense.domain.enums.WorkflowStatus;
import com.chh.autosense.exception.*;
import com.chh.autosense.mapper.WorkflowExecutionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Database fencing is authoritative; no expired lease grants permission to resend a command. */
@Service
public class WorkflowClaimService {
    private final WorkflowExecutionMapper workflows;
    private final GraphProperties properties;
    public WorkflowClaimService(WorkflowExecutionMapper workflows, GraphProperties properties) {
        this.workflows = workflows; this.properties = properties;
    }
    public record Claim(String requestId, String owner, long fence) { }

    @Transactional
    public Claim acquire(String requestId, long userId, long expectedVersion) {
        var workflow = ownedLocked(requestId, userId);
        if (WorkflowStatus.valueOf(workflow.getStatus()).terminal()) throw conflict("WORKFLOW_NOT_RESUMABLE");
        String owner = UUID.randomUUID().toString();
        if (workflows.claim(requestId, expectedVersion, owner, properties.executionSliceTimeoutSeconds()) != 1)
            throw conflict("WORKFLOW_BUSY");
        return new Claim(requestId, owner, workflow.getFence() + 1);
    }

    @Transactional
    public void release(Claim claim) { workflows.releaseClaim(claim.requestId(), claim.fence(), claim.owner()); }

    /** Caller must hold a short write transaction until its update/insert commits. */
    public WorkflowExecution requireFence(String requestId, long fence) {
        var workflow = workflows.lock(requestId);
        if (workflow == null || workflow.getFence() != fence || workflow.getLeaseOwner() == null
                || workflow.getLeaseUntil() == null || !workflow.getLeaseUntil().isAfter(workflows.databaseNow()))
            throw conflict("WORKFLOW_CLAIM_LOST");
        return workflow;
    }

    public WorkflowExecution ownedLocked(String requestId, long userId) {
        var workflow = workflows.lock(requestId);
        if (workflow == null) throw new ApiException(ErrorCode.SESSION_NOT_FOUND, "工作流不存在");
        if (!Objects.equals(workflow.getUserId(), userId)) throw new ApiException(ErrorCode.FORBIDDEN, "无权访问此工作流");
        return workflow;
    }
    public static ApiException conflict(String code) {
        ErrorCode value;
        try { value = ErrorCode.valueOf(code); } catch (IllegalArgumentException e) { value = ErrorCode.WORKFLOW_BUSY; }
        return new ApiException(value, code);
    }
}
