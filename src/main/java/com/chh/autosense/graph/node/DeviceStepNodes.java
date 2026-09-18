package com.chh.autosense.graph.node;

import com.chh.autosense.config.GraphProperties;
import com.chh.autosense.domain.enums.WorkflowStatus;
import com.chh.autosense.domain.enums.PlanStepType;
import com.chh.autosense.graph.state.*;
import java.time.Clock;
import java.util.*;
import static com.chh.autosense.graph.state.AssistantState.*;

/** Shared confirmation boundary. Target resolution is restricted to local metadata. */
public final class DeviceStepNodes {
    private final WorkflowStepActions actions;
    private final GraphProperties properties;
    private final Clock clock;
    public DeviceStepNodes(WorkflowStepActions actions, GraphProperties properties, Clock clock) {
        this.actions = actions; this.properties = properties; this.clock = clock;
    }

    public Map<String, Object> resolve(AssistantState state) throws Exception {
        var target = actions.resolveTarget(state);
        if (target.isEmpty()) {
            var p = state.plan();
            var delta = GraphUpdates.event(state, WorkflowStatus.WAITING_INPUT, "STATUS", "TARGET_REQUIRED", "请补充设备信息。", Map.of());
            delta.put(PLAN, new PlanContext(p.executionPlan(), p.currentStep(), p.runtimeInputs(), p.results(),
                    p.candidateOutcome(), "请补充本步骤的设备信息，新增目标需要重新规划。"));
            return delta;
        }
        var command = new LinkedHashMap<>(state.plan().runtimeInputs());
        command.putAll(target);
        if (state.plan().step().type() == PlanStepType.DEVICE_QUERY) command.clear();
        var old = state.control();
        String key = state.request().requestId() + ":" + state.plan().step().stepId();
        var control = old.command().equals(command) && old.idempotencyKey().equals(key) ? old :
                new ControlContext(command, "", false, "", null, key, Map.of());
        return Map.of(DEVICE, new DeviceContext(target, Map.of()), CONTROL, control);
    }

    public Map<String, Object> validate(AssistantState state) throws Exception {
        actions.revalidate(state);
        var c = state.control();
        return Map.of(CONTROL, new ControlContext(c.command(), c.commandExecutionId(), true, "REQUIRES_CONFIRMATION",
                c.approvalRef(), c.idempotencyKey(), c.executionResult()));
    }

    public static String scope(AssistantState state) {
        return PlanValidator.digest(Map.of("requestId", state.request().requestId(), "userId", state.request().userId(),
                "stepId", state.plan().step().stepId(), "type", state.plan().step().type(), "parameters", parameters(state)));
    }

    private static Map<String, Object> parameters(AssistantState state) {
        if (state.plan().step().type() != PlanStepType.DEVICE_QUERY) return state.control().command();
        var parameters = new LinkedHashMap<>(state.plan().runtimeInputs());
        parameters.putAll(state.<DeviceContext>value(DEVICE).orElseThrow().resolved());
        return parameters;
    }

    public boolean approved(AssistantState state) {
        var approval = state.control().approvalRef();
        return approval != null && approval.status().equals("APPROVED") && approval.userId() == state.request().userId()
                && approval.stepId().equals(state.plan().step().stepId()) && approval.scopeHash().equals(scope(state))
                && approval.expiresAt() != null && approval.expiresAt().isAfter(clock.instant());
    }

    public Map<String, Object> prepareApproval(AssistantState state) {
        if (approved(state)) return Map.of();
        var approval = new Approval(UUID.randomUUID().toString(), state.plan().step().stepId(), state.request().userId(),
                scope(state), "PENDING", clock.instant().plusSeconds(properties.approvalTtlSeconds()));
        var c = state.control();
        var delta = GraphUpdates.event(state, WorkflowStatus.WAITING_APPROVAL, "CONFIRM", "WAITING_APPROVAL",
                "请确认是否执行此设备步骤。", Map.of("approvalId", approval.approvalId(), "stepId", approval.stepId(),
                        "expiresAt", approval.expiresAt().toString(), "operation", state.plan().step().type().name(), "parameters", parameters(state)));
        delta.put(CONTROL, new ControlContext(c.command(), c.commandExecutionId(), c.permission(), c.risk(), approval,
                c.idempotencyKey(), c.executionResult()));
        // Human wait ends the active execution slice, but preserves the used retry count.
        var cache = new LinkedHashMap<>(state.retry().completedCalls()); cache.remove("sliceDeadline");
        delta.put(RETRY, new RetryContext(state.retry().retriesUsed(), "", state.retry().latestFailure(), null, cache));
        return delta;
    }

    public Map<String, Object> awaitApproval(AssistantState state) {
        var approval = state.control().approvalRef();
        if (approval != null && approval.status().equals("REJECTED"))
            return GraphUpdates.failure(state, "APPROVAL_REJECTED", ExecutionPlan.Certainty.NOT_SENT);
        return approved(state) ? GraphUpdates.event(state, WorkflowStatus.RUNNING, "STATUS", "APPROVED",
                "已确认，正在重新校验。", Map.of()) : Map.of();
    }

    public Map<String, Object> revalidate(AssistantState state) throws Exception {
        if (!approved(state)) return Map.of();
        return validate(state);
    }

    public Map<String, Object> prepareCommand(AssistantState state) throws Exception {
        if (!approved(state)) throw new SecurityException("Approval is no longer valid");
        actions.revalidate(state);
        var c = state.control();
        String id = c.commandExecutionId().isBlank() ? UUID.nameUUIDFromBytes(c.idempotencyKey()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString() : c.commandExecutionId();
        var delta = Map.<String, Object>of(CONTROL, new ControlContext(c.command(), id, c.permission(), c.risk(),
                c.approvalRef(), c.idempotencyKey(), c.executionResult()));
        actions.prepareCommand(GraphUpdates.apply(state, delta));
        return delta;
    }
}
