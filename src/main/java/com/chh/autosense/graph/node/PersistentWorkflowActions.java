package com.chh.autosense.graph.node;

import com.chh.autosense.core.session.*;
import com.chh.autosense.graph.state.*;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeoutException;
import static com.chh.autosense.graph.state.AssistantState.*;

/** Persistence decorates capability calls; routing and continuation remain graph edges. */
public final class PersistentWorkflowActions implements WorkflowStepActions {
    @Override public boolean streamsAnswers() { return delegate.streamsAnswers(); }
    private final WorkflowStepActions delegate;
    private final WorkflowPersistenceService persistence;
    private final WorkflowApprovalService approvals;
    private final CommandExecutionService commands;
    public PersistentWorkflowActions(WorkflowStepActions delegate, WorkflowPersistenceService persistence,
            WorkflowApprovalService approvals, CommandExecutionService commands) {
        this.delegate = delegate; this.persistence = persistence; this.approvals = approvals; this.commands = commands;
    }
    @Override public PlanProposal plan(AssistantState state, Duration remaining) throws Exception { return delegate.plan(state, remaining); }
    @Override public Map<String, Object> resolveTarget(AssistantState state) throws Exception { return delegate.resolveTarget(state); }
    @Override public void revalidate(AssistantState state) throws Exception { delegate.revalidate(state); }
    @Override public Map<String, Object> answer(AssistantState state, Duration remaining) throws Exception { return delegate.answer(state, remaining); }
    @Override public Map<String, Object> diagnose(AssistantState state, Duration remaining) throws Exception { return delegate.diagnose(state, remaining); }
    @Override public Map<String, Object> query(AssistantState state, Duration remaining) throws Exception {
        persistence.beginQuery(state); return delegate.query(state, remaining);
    }
    @Override public void prepareCommand(AssistantState state) { commands.prepare(state); }
    @Override public Map<String, Object> control(AssistantState state, Duration remaining) throws Exception {
        String attempt = commands.begin(state);
        Map<String, Object> result = delegate.control(state, remaining);
        commands.finish(state, attempt, result, "", ExecutionPlan.Certainty.SUCCEEDED, false);
        return result;
    }
    @Override public void commitResult(AssistantState state, ExecutionPlan.Result result) { persistence.commitResult(state, result); }
    @Override public boolean supportsSafeCommandRetry() { return delegate.supportsSafeCommandRetry(); }

    @Override public Map<String, Object> afterNode(AssistantState before, Map<String, Object> delta) {
        var change = new LinkedHashMap<>(delta);
        change = new LinkedHashMap<>(commands.reconcileAttempt(before, change));
        var state = GraphUpdates.apply(before, change);
        if (change.get(CONTROL) instanceof ControlContext control && control.approvalRef() != null
                && control.approvalRef().status().equals("PENDING") && change.get(OUTPUT) instanceof OutputContext output
                && output.type().equals("CONFIRM")) {
            var reference = approvals.prepare(state);
            change.put(CONTROL, new ControlContext(control.command(), control.commandExecutionId(), control.permission(), control.risk(),
                    reference, control.idempotencyKey(), control.executionResult()));
            var data = new LinkedHashMap<>(output.data());
            @SuppressWarnings("unchecked") var payload = new LinkedHashMap<>((Map<String, Object>) data.get("payload"));
            payload.put("approvalId", reference.approvalId()); payload.put("expiresAt", reference.expiresAt().toString()); data.put("payload", payload);
            change.put(OUTPUT, new OutputContext(output.type(), output.code(), output.message(), data));
        }
        return persistence.project(before, change);
    }
}
