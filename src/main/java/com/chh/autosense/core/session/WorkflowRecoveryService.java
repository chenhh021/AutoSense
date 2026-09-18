package com.chh.autosense.core.session;

import com.chh.autosense.core.security.AuthUser;
import com.chh.autosense.domain.enums.WorkflowStatus;
import com.chh.autosense.graph.node.GraphUpdates;
import com.chh.autosense.graph.state.*;
import com.chh.autosense.mapper.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import static com.chh.autosense.graph.state.AssistantState.*;

/** Restart discovery only suspends. Recovery executes only after an authenticated continuation request. */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowRecoveryService {
    private final WorkflowExecutionMapper workflows;
    private final WorkflowStepMapper steps;
    private final WorkflowApprovalMapper approvals;
    private final CommandExecutionMapper commands;
    private final WorkflowClaimService claims;
    private final WorkflowCheckpointService checkpoints;
    private final WorkflowApprovalService approvalService;
    private final ChatMessageMapper messages;
    private final WorkflowAuditService audit;
    private final ObjectMapper json;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void suspendAfterRestart() {
        int count = workflows.suspendAfterRestart();
        log.info("Workflow restart reconciliation completed: suspendedCount={}", count);
    }

    public record Restored(AssistantState state, WorkflowClaimService.Claim claim, String asNode) { }

    @Transactional
    public Restored cancel(AuthUser user, long sessionId, String requestId, long version) {
        var row = claims.ownedLocked(requestId, user.userId());
        var restored = restore(user, sessionId, requestId, version, row.getStatus().equals("WAITING_RESUME"));
        var state = restored.state(); var plan = state.plan();
        var results = new LinkedHashMap<>(plan.results());
        for (var step : plan.executionPlan().steps()) results.putIfAbsent(step.stepId(), new ExecutionPlan.Result(
                ExecutionPlan.StepStatus.NOT_EXECUTED, Map.of(), "WORKFLOW_CANCELLED", ExecutionPlan.Certainty.NOT_SENT, 0));
        state = GraphUpdates.apply(state, Map.of(PLAN, new PlanContext(plan.executionPlan(), plan.currentStep(), plan.runtimeInputs(),
                results, plan.candidateOutcome(), plan.clarifyQuestion())));
        state = GraphUpdates.apply(state, GraphUpdates.event(state, WorkflowStatus.CANCELLED, "STATUS", "WORKFLOW_CANCELLED",
                "已取消剩余步骤，已发生的操作和结果仍然保留。", Map.of()));
        row = workflows.lock(requestId);
        audit.append(row, "cancel:" + restored.claim().fence(), "WORKFLOW_CANCELLED", null, null, null, "RECORDED",
                "WORKFLOW_CANCELLED", null, Map.of("status", "CANCELLED"));
        return new Restored(state, restored.claim(), "PlanRouter");
    }

    @Transactional
    public Restored restore(AuthUser user, long sessionId, String requestId, long version, boolean explicitResume) {
        var workflow = claims.ownedLocked(requestId, user.userId());
        if (workflow.getSessionId() != sessionId) throw WorkflowClaimService.conflict("WORKFLOW_NOT_RESUMABLE");
        if (WorkflowStatus.valueOf(workflow.getStatus()).terminal()) throw WorkflowClaimService.conflict("WORKFLOW_NOT_RESUMABLE");
        if (explicitResume != workflow.getStatus().equals("WAITING_RESUME")) throw WorkflowClaimService.conflict("WORKFLOW_NOT_RESUMABLE");
        var saved = checkpoints.get(requestId, null).orElseThrow(() -> WorkflowClaimService.conflict("CHECKPOINT_UNAVAILABLE"));
        var claim = claims.acquire(requestId, user.userId(), version);
        workflow = workflows.lock(requestId);
        var state = new AssistantState(saved.getState());
        if (state.request().conversationId() != sessionId || state.request().userId() != user.userId())
            throw WorkflowClaimService.conflict("CHECKPOINT_IDENTITY_MISMATCH");
        var data = new LinkedHashMap<>(state.data());
        var definitions = workflow.getPlanJson() == null ? state.plan().executionPlan() : read(workflow.getPlanJson(), ExecutionPlan.class);
        int cursor = workflow.getCurrentStepIndex();
        var results = new LinkedHashMap<>(state.plan().results());
        var rows = steps.forWorkflow(requestId);
        for (var row : rows) {
            if (Set.of("COMPLETED", "SKIPPED", "FAILED", "REJECTED", "NOT_EXECUTED").contains(row.getStatus())) {
                results.put(row.getStepId(), new ExecutionPlan.Result(ExecutionPlan.StepStatus.valueOf(row.getStatus()), object(row.getResultJson()),
                        Objects.toString(row.getFailureCode(), ""), ExecutionPlan.Certainty.valueOf(row.getCertainty()), row.getRetriesUsed()));
            }
        }
        data.put(PLAN, new PlanContext(definitions, cursor, state.plan().runtimeInputs(), results, state.plan().candidateOutcome(), state.plan().clarifyQuestion()));
        WorkflowStatus status = WorkflowStatus.valueOf(explicitResume ? Objects.toString(workflow.getSuspendedStatus(), "RUNNING") : workflow.getStatus());
        if (status == WorkflowStatus.WAITING_RESUME) status = WorkflowStatus.RUNNING;
        var w = state.workflow();
        data.put(WORKFLOW, new WorkflowContext(status, cursor, w.progress(), workflow.getVersion(), w.inputRequestId(), w.prompt(), w.returnNode(),
                w.failureCode(), w.schemaVersion(), w.graphVersion(), workflow.getLastEventSequence(), claim.fence()));
        String asNode = null;
        if (cursor != state.plan().currentStep()) {
            data.put(CONTROL, new ControlContext(Map.of(), "", false, "", null, "", Map.of()));
            data.put(DEVICE, new DeviceContext(Map.of(), Map.of()));
            data.put(DIAGNOSIS, new DiagnosisContext(Map.of(), List.of(), Map.of(), Map.of()));
            asNode = "CompleteStep";
        }
        var completedCalls = new LinkedHashMap<>(state.retry().completedCalls()); completedCalls.remove("sliceDeadline");
        int used = cursor < rows.size() ? Math.max(rows.get(cursor).getRetriesUsed(), cursor == state.plan().currentStep() ? state.retry().retriesUsed() : 0) : 0;
        data.put(RETRY, new RetryContext(used, "", state.retry().latestFailure(), null, cursor == state.plan().currentStep() ? completedCalls : Map.of()));
        if (cursor < definitions.steps().size()) {
            String stepId = definitions.steps().get(cursor).stepId();
            if (results.containsKey(stepId)) asNode = "PlanRouter";
            var approval = approvals.current(requestId, stepId);
            if (approval == null && state.control().approvalRef() != null)
                approval = approvals.selectOneById(state.control().approvalRef().approvalId());
            if (approval != null && approval.getStepId().equals(stepId)) {
                var control = (ControlContext) data.get(CONTROL);
                data.put(CONTROL, new ControlContext(control.command(), control.commandExecutionId(), control.permission(), control.risk(),
                        approvalService.reference(approval), control.idempotencyKey(), control.executionResult()));
            }
        }
        var auditContext = state.<AuditContext>value(AUDIT).orElseThrow();
        if (!Objects.equals(auditContext.messageId(), workflow.getLatestInputMessageId())) {
            var current = messages.selectOneById(workflow.getLatestInputMessageId());
            if (current == null || current.getSessionId() != sessionId || !current.getRole().equals("USER"))
                throw WorkflowClaimService.conflict("INPUT_MESSAGE_UNAVAILABLE");
            var history = new ArrayList<>(messages.historyBefore(sessionId, current.getId()).stream()
                    .sorted(Comparator.comparingLong(com.chh.autosense.domain.entity.ChatMessage::getId))
                    .map(m -> new Message(m.getId(), m.getRole(), m.getContent())).toList());
            history.add(new Message(current.getId(), "USER", current.getContent())); data.put(MESSAGES, history);
            var p = (PlanContext) data.get(PLAN); var inputs = new LinkedHashMap<>(p.runtimeInputs()); inputs.put("clarification", current.getContent());
            data.put(PLAN, new PlanContext(p.executionPlan(), p.currentStep(), inputs, p.results(), p.candidateOutcome(), p.clarifyQuestion()));
            data.put(AUDIT, new AuditContext(requestId, workflow.getReportId(), current.getId(), auditContext.round(), auditContext.commandExecutionRefs(), auditContext.auditEventRefs()));
        }
        state = new AssistantState(data);
        if (cursor < definitions.steps().size()) {
            var result = results.get(definitions.steps().get(cursor).stepId());
            if (result != null && !result.successful() && result.status() != ExecutionPlan.StepStatus.SKIPPED)
                state = GraphUpdates.apply(state, GraphUpdates.failure(state, result.failureCode(), result.certainty()));
        }
        for (var command : commands.forWorkflow(requestId)) {
            if (Set.of("IN_FLIGHT", "UNKNOWN").contains(command.getStatus())) {
                command.setStatus("UNKNOWN"); command.setCertainty("UNKNOWN"); command.setFailureCode("DEVICE_RESULT_UNKNOWN"); commands.update(command);
                state = GraphUpdates.apply(state, GraphUpdates.failure(state, "DEVICE_RESULT_UNKNOWN", ExecutionPlan.Certainty.UNKNOWN));
                asNode = "PlanRouter";
                audit.append(workflow, "command:" + command.getCommandId() + ":unknown", "COMMAND_RESULT_UNKNOWN", command.getStepId(), command.getCommandId(),
                        command.getActiveAttemptId(), "FAILED", "DEVICE_RESULT_UNKNOWN", null, Map.of("certainty", "UNKNOWN", "stepType", "DEVICE_CONTROL"));
            }
        }
        workflow.setStatus(state.workflow().status().name()); workflow.setSuspendedStatus(null); workflows.update(workflow, false);
        audit.append(workflow, "resume:" + claim.fence(), "WORKFLOW_RESUMED", null, null, null, "RECORDED", "RESUMED", null, Map.of("fence", claim.fence()));
        return new Restored(state, claim, asNode);
    }

    @Transactional
    public void suspendFailedExecution(WorkflowClaimService.Claim claim) {
        var workflow = workflows.lock(claim.requestId());
        if (workflow == null || workflow.getFence() != claim.fence() || WorkflowStatus.valueOf(workflow.getStatus()).terminal()) return;
        workflow.setSuspendedStatus(workflow.getStatus()); workflow.setStatus("WAITING_RESUME");
        workflow.setVersion(workflow.getVersion() + 1); workflows.update(workflow);
    }
    private <T> T read(String text, Class<T> type) {
        try { return json.readValue(text, type); } catch (Exception e) { throw new IllegalStateException("Invalid stored workflow data", e); }
    }
    private Map<String, Object> object(String text) {
        if (text == null) return Map.of();
        try { return json.readValue(text, new TypeReference<>() { }); } catch (Exception e) { throw new IllegalStateException("Invalid stored step result", e); }
    }
}
