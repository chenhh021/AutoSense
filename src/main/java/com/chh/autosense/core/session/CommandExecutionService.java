package com.chh.autosense.core.session;

import com.chh.autosense.config.GraphProperties;
import com.chh.autosense.domain.entity.CommandExecution;
import com.chh.autosense.graph.node.*;
import com.chh.autosense.graph.state.*;
import com.chh.autosense.mapper.*;
import com.chh.autosense.domain.enums.WorkflowStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** One logical command per control step, with a durable claim before any outgoing write. */
@Service
public class CommandExecutionService {
    private final CommandExecutionMapper commands;
    private final WorkflowStepMapper steps;
    private final WorkflowClaimService claims;
    private final WorkflowApprovalService approvals;
    private final WorkflowAuditService audit;
    private final WorkflowPersistenceService persistence;
    private final GraphProperties properties;
    public CommandExecutionService(CommandExecutionMapper commands, WorkflowStepMapper steps, WorkflowClaimService claims,
            WorkflowApprovalService approvals, WorkflowAuditService audit, WorkflowPersistenceService persistence, GraphProperties properties) {
        this.commands = commands; this.steps = steps; this.claims = claims; this.approvals = approvals;
        this.audit = audit; this.persistence = persistence; this.properties = properties;
    }

    @Transactional
    public void prepare(AssistantState state) {
        var workflow = claims.requireFence(state.request().requestId(), state.workflow().executionFence());
        approvals.requireApproved(state);
        String stepId = state.plan().step().stepId();
        var step = steps.lock(workflow.getRequestId(), stepId);
        if (step == null || !step.getType().equals("DEVICE_CONTROL")) throw WorkflowClaimService.conflict("INVALID_CONTROL_STEP");
        String hash = PlanValidator.digest(state.control().command());
        // The command reference and command row are committed together under the parent workflow lock.
        // Avoid a FOR UPDATE lookup on an absent command, which can gap-lock another workflow's insert.
        var existing = step.getCommandExecutionId() == null ? null : commands.lockStep(workflow.getRequestId(), stepId);
        if (step.getCommandExecutionId() != null && existing == null) throw WorkflowClaimService.conflict("COMMAND_NOT_PREPARED");
        if (existing != null) {
            if (!existing.getParamsHash().equals(hash) || !existing.getScopeHash().equals(state.control().approvalRef().scopeHash()))
                throw WorkflowClaimService.conflict("COMMAND_SCOPE_MISMATCH");
            return;
        }
        Object ref = state.control().command().get("deviceRef");
        if (!(ref instanceof Number deviceId)) throw WorkflowClaimService.conflict("INVALID_CONTROL_TARGET");
        var command = new CommandExecution();
        command.setCommandId(state.control().commandExecutionId()); command.setRequestId(workflow.getRequestId()); command.setStepId(stepId);
        command.setOperationKey(workflow.getRequestId() + ":" + stepId); command.setSessionId(workflow.getSessionId());
        command.setUserId(workflow.getUserId()); command.setDeviceId(deviceId.longValue());
        command.setActionCode(Objects.toString(state.control().command().get("action"), "DEVICE_CONTROL"));
        command.setCanonicalParams(persistence.encode(state.control().command())); command.setParamsHash(hash);
        command.setApprovalId(state.control().approvalRef().approvalId()); command.setScopeHash(state.control().approvalRef().scopeHash());
        command.setStatus("PREPARED"); command.setCertainty("NOT_SENT"); command.setMaxRetries(properties.maxRetries()); command.setFence(workflow.getFence());
        commands.insertSelective(command); step.setCommandExecutionId(command.getCommandId()); steps.update(step);
        audit.append(workflow, "command:" + stepId + ":prepared", "COMMAND_PREPARED", stepId, command.getCommandId(), null,
                "RECORDED", "PREPARED", null, Map.of("stepType", "DEVICE_CONTROL", "certainty", "NOT_SENT"));
    }

    @Transactional
    public String begin(AssistantState state) {
        var workflow = claims.requireFence(state.request().requestId(), state.workflow().executionFence());
        approvals.requireApproved(state);
        var command = commands.lockStep(workflow.getRequestId(), state.plan().step().stepId());
        if (command == null) throw WorkflowClaimService.conflict("COMMAND_NOT_PREPARED");
        if (!command.getParamsHash().equals(PlanValidator.digest(state.control().command()))
                || !command.getScopeHash().equals(state.control().approvalRef().scopeHash()))
            throw WorkflowClaimService.conflict("COMMAND_SCOPE_MISMATCH");
        if (Set.of("IN_FLIGHT", "UNKNOWN").contains(command.getStatus())) throw new StepFailure("DEVICE_RESULT_UNKNOWN", ExecutionPlan.Certainty.UNKNOWN);
        if (!Set.of("PREPARED", "RETRYING").contains(command.getStatus()) || state.retry().retriesUsed() > command.getMaxRetries())
            throw new StepFailure("COMMAND_ALREADY_FINISHED", ExecutionPlan.Certainty.valueOf(command.getCertainty()));
        String attemptId = UUID.randomUUID().toString();
        command.setApprovalId(state.control().approvalRef().approvalId());
        commands.update(command);
        if (commands.beginAttempt(command.getCommandId(), command.getVersion(), workflow.getFence(), attemptId, state.retry().retriesUsed()) != 1)
            throw WorkflowClaimService.conflict("COMMAND_ATTEMPT_CONFLICT");
        audit.append(workflow, "attempt:" + attemptId + ":started", "COMMAND_ATTEMPT_STARTED", command.getStepId(), command.getCommandId(), attemptId,
                "RECORDED", "IN_FLIGHT", null, Map.of("stepType", "DEVICE_CONTROL", "certainty", "IN_FLIGHT", "fence", workflow.getFence()));
        return attemptId;
    }

    /** The graph thread settles timeouts even when the provider ignores interruption. */
    @Transactional
    public Map<String, Object> reconcileAttempt(AssistantState before, Map<String, Object> delta) {
        if (before.plan().executionPlan().steps().isEmpty()
                || before.plan().currentStep() >= before.plan().executionPlan().steps().size()
                || before.plan().step().type() != com.chh.autosense.domain.enums.PlanStepType.DEVICE_CONTROL)
            return delta;
        var after = GraphUpdates.apply(before, delta);
        var result = after.plan().results().get(before.plan().step().stepId());
        boolean retrying = after.workflow().status() == WorkflowStatus.RETRYING;
        if (result == null && !retrying) return delta;
        var workflow = claims.requireFence(before.request().requestId(), before.workflow().executionFence());
        var command = commands.lockStep(workflow.getRequestId(), before.plan().step().stepId());
        if (command == null) return delta;
        if (command.getStatus().equals("SUCCEEDED")) {
            if (!retrying && result != null && result.successful()) return delta;
            var step = steps.lock(workflow.getRequestId(), command.getStepId());
            var committed = persistence.result(step);
            var corrected = new LinkedHashMap<>(delta);
            corrected.putAll(GraphUpdates.result(before, committed));
            corrected.putAll(GraphUpdates.event(GraphUpdates.apply(before, corrected), WorkflowStatus.RUNNING,
                    "STATUS", "RUNNING", "设备操作结果已保存。", Map.of()));
            return corrected;
        }
        if (command.getStatus().equals("IN_FLIGHT")) {
            finish(before, command.getActiveAttemptId(), result == null ? Map.of() : result.data(),
                    result == null ? "REQUEST_TIMEOUT" : result.failureCode(),
                    result == null ? ExecutionPlan.Certainty.UNKNOWN : result.certainty(), retrying);
        }
        return delta;
    }

    @Transactional
    public void finish(AssistantState state, String attemptId, Map<String, Object> data, String failureCode,
                       ExecutionPlan.Certainty certainty, boolean retrying) {
        var workflow = claims.requireFence(state.request().requestId(), state.workflow().executionFence());
        var command = commands.lockStep(workflow.getRequestId(), state.plan().step().stepId());
        if (command == null) throw WorkflowClaimService.conflict("COMMAND_NOT_PREPARED");
        String status = retrying ? "RETRYING" : certainty == ExecutionPlan.Certainty.SUCCEEDED ? "SUCCEEDED"
                : certainty == ExecutionPlan.Certainty.UNKNOWN ? "UNKNOWN" : "FAILED";
        if (commands.finishAttempt(command.getCommandId(), state.workflow().executionFence(), attemptId, status,
                certainty.name(), persistence.encode(data), failureCode) != 1) throw WorkflowClaimService.conflict("COMMAND_ATTEMPT_CONFLICT");
        if (!retrying) persistence.commitResult(state, new ExecutionPlan.Result(certainty == ExecutionPlan.Certainty.SUCCEEDED
                ? ExecutionPlan.StepStatus.COMPLETED : ExecutionPlan.StepStatus.FAILED, data, failureCode, certainty, state.retry().retriesUsed()));
        audit.append(workflow, "attempt:" + attemptId + ":result", "COMMAND_ATTEMPT_RESULT", command.getStepId(), command.getCommandId(), attemptId,
                certainty == ExecutionPlan.Certainty.SUCCEEDED ? "SUCCESS" : "FAILED", failureCode, null,
                Map.of("stepType", "DEVICE_CONTROL", "certainty", certainty.name(), "retriesUsed", state.retry().retriesUsed()));
    }
}
