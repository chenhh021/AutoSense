package com.chh.autosense.core.session;

import com.chh.autosense.core.security.AuthUser;
import com.chh.autosense.domain.entity.WorkflowApproval;
import com.chh.autosense.graph.state.AssistantState;
import com.chh.autosense.mapper.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

@Service
public class WorkflowApprovalService {
    private final WorkflowApprovalMapper approvals;
    private final WorkflowStepMapper steps;
    private final WorkflowExecutionMapper workflows;
    private final WorkflowClaimService claims;
    private final WorkflowAuditService audit;
    private final ObjectMapper json;
    public WorkflowApprovalService(WorkflowApprovalMapper approvals, WorkflowStepMapper steps, WorkflowExecutionMapper workflows,
            WorkflowClaimService claims, WorkflowAuditService audit, ObjectMapper json) {
        this.approvals = approvals; this.steps = steps; this.workflows = workflows; this.claims = claims; this.audit = audit; this.json = json;
    }

    @Transactional
    public AssistantState.Approval prepare(AssistantState state) {
        var workflow = claims.requireFence(state.request().requestId(), state.workflow().executionFence());
        var proposal = Objects.requireNonNull(state.control().approvalRef());
        var step = steps.lock(workflow.getRequestId(), proposal.stepId());
        if (step == null || !Set.of("DEVICE_QUERY", "DEVICE_CONTROL").contains(step.getType())
                || proposal.userId() != workflow.getUserId()) throw WorkflowClaimService.conflict("APPROVAL_SCOPE_MISMATCH");
        var current = approvals.current(workflow.getRequestId(), proposal.stepId());
        LocalDateTime now = workflows.databaseNow();
        if (current != null && current.getScopeHash().equals(proposal.scopeHash()) && current.getExpiresAt().isAfter(now)) return reference(current);
        if (current != null) approvals.invalidate(workflow.getRequestId(), proposal.stepId(),
                current.getExpiresAt().isAfter(now) ? "INVALIDATED" : "EXPIRED");
        var row = new WorkflowApproval(); row.setApprovalId(proposal.approvalId()); row.setRequestId(workflow.getRequestId());
        row.setStepId(proposal.stepId()); row.setUserId(workflow.getUserId()); row.setScopeHash(proposal.scopeHash());
        row.setOperationKind(step.getType()); row.setStatus("PENDING"); row.setExpiresAt(LocalDateTime.ofInstant(proposal.expiresAt(), ZoneOffset.UTC));
        row.setVersion(0L);
        var target = state.<AssistantState.DeviceContext>value(AssistantState.DEVICE).orElseThrow().resolved();
        try { row.setDeviceRefs(json.writeValueAsString(target.getOrDefault("deviceRef", target.getOrDefault("deviceRefs", List.of())))); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("Invalid approval target", e); }
        approvals.insertSelective(row);
        audit.append(workflow, "approval:" + row.getApprovalId() + ":pending", "APPROVAL_REQUESTED", row.getStepId(), null, null,
                "RECORDED", "WAITING_APPROVAL", null, Map.of("approvalId", row.getApprovalId(), "scopeHash", row.getScopeHash(), "stepType", step.getType()));
        return reference(row);
    }

    public record Decision(AssistantState.Approval approval, long version, boolean execute) { }

    @Transactional
    public Decision decide(AuthUser user, long sessionId, String requestId, String stepId, String approvalId,
                           boolean approved, long expectedVersion) {
        var workflow = claims.ownedLocked(requestId, user.userId());
        com.chh.autosense.graph.checkpoint.AssistantStateSerializer.requireExecutable(workflow.getSchemaVersion(), workflow.getGraphVersion());
        if (workflow.getSessionId() != sessionId) throw WorkflowClaimService.conflict("APPROVAL_SCOPE_MISMATCH");
        var row = approvals.lock(approvalId);
        if (row == null || !row.getRequestId().equals(requestId) || !row.getStepId().equals(stepId)
                || !row.getUserId().equals(user.userId())) throw WorkflowClaimService.conflict("APPROVAL_SCOPE_MISMATCH");
        String decision = approved ? "APPROVED" : "REJECTED";
        if (row.getStatus().equals(decision)) return new Decision(reference(row), workflow.getVersion(), false);
        if (workflow.getVersion() != expectedVersion) throw WorkflowClaimService.conflict("VERSION_CONFLICT");
        if (workflow.getLeaseOwner() != null && workflow.getLeaseUntil() != null && workflow.getLeaseUntil().isAfter(workflows.databaseNow()))
            throw WorkflowClaimService.conflict("WORKFLOW_BUSY");
        if (!Set.of("WAITING_APPROVAL", "WAITING_RESUME").contains(workflow.getStatus())
                || !row.getStatus().equals("PENDING"))
            throw WorkflowClaimService.conflict("APPROVAL_SCOPE_MISMATCH");
        var step = steps.lock(requestId, stepId);
        if (step == null || step.getOrdinal().intValue() != workflow.getCurrentStepIndex().intValue())
            throw WorkflowClaimService.conflict("APPROVAL_SCOPE_MISMATCH");
        if (!row.getExpiresAt().isAfter(workflows.databaseNow())) {
            row.setStatus("EXPIRED"); row.setVersion(row.getVersion() + 1); approvals.update(row);
            audit.append(workflow, "approval:" + approvalId + ":expired", "APPROVAL_EXPIRED", stepId, null, null,
                    "RECORDED", "APPROVAL_EXPIRED", null, Map.of("approvalId", approvalId, "stepType", step.getType()));
            return new Decision(reference(row), workflow.getVersion(), workflow.getStatus().equals("WAITING_APPROVAL"));
        }
        row.setStatus(decision); row.setDecisionAt(workflows.databaseNow()); row.setVersion(row.getVersion() + 1); approvals.update(row);
        audit.append(workflow, "approval:" + approvalId + ":decision", "APPROVAL_DECIDED", stepId, null, null,
                "RECORDED", decision, null, Map.of("approvalId", approvalId, "decision", decision, "stepType", step.getType()));
        return new Decision(reference(row), workflow.getVersion(), workflow.getStatus().equals("WAITING_APPROVAL"));
    }

    /** Called in the same short transaction as a device attempt claim. */
    public void requireApproved(AssistantState state) {
        var ref = state.control().approvalRef();
        if (ref == null || !ref.scopeHash().equals(com.chh.autosense.graph.node.DeviceStepNodes.scope(state)))
            throw WorkflowClaimService.conflict("APPROVAL_SCOPE_MISMATCH");
        var row = approvals.lock(ref.approvalId());
        if (row == null || !row.getStatus().equals("APPROVED") || !row.getScopeHash().equals(ref.scopeHash())
                || !row.getRequestId().equals(state.request().requestId()) || !row.getStepId().equals(state.plan().step().stepId())
                || row.getUserId() != state.request().userId() || !row.getExpiresAt().isAfter(workflows.databaseNow()))
            throw WorkflowClaimService.conflict("APPROVAL_SCOPE_MISMATCH");
    }

    public AssistantState.Approval reference(WorkflowApproval row) {
        return new AssistantState.Approval(row.getApprovalId(), row.getStepId(), row.getUserId(), row.getScopeHash(),
                row.getStatus(), row.getExpiresAt().toInstant(ZoneOffset.UTC));
    }
}
