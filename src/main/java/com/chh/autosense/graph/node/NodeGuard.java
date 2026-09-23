package com.chh.autosense.graph.node;

import com.chh.autosense.graph.state.AssistantState;
import com.chh.autosense.graph.state.ExecutionPlan;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.action.NodeAction;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

@lombok.extern.slf4j.Slf4j
public final class NodeGuard {
    private NodeGuard() { }
    public static AsyncNodeAction<AssistantState> guarded(NodeAction<AssistantState> node) {
        return guarded(node, false);
    }
    public static AsyncNodeAction<AssistantState> guarded(NodeAction<AssistantState> node, boolean simulated) {
        return guarded(node, simulated, null);
    }
    public static AsyncNodeAction<AssistantState> guarded(NodeAction<AssistantState> node, boolean simulated, WorkflowStepActions actions) {
        return observed(state -> {
            if (failed(state)) return java.util.Map.of();
            try { return node.apply(state); }
            catch (Exception e) {
                log.warn("Workflow node failed: errorType={}", e.getClass().getSimpleName());
                if (e instanceof com.chh.autosense.exception.ApiException api)
                    return GraphUpdates.failure(state, api.errorCode().name(), ExecutionPlan.Certainty.NOT_SENT);
                if (e instanceof StepFailure failure) return GraphUpdates.failure(state, failure.code(), failure.certainty(), failure.data());
                return GraphUpdates.failure(state, e instanceof SecurityException ? "EXECUTION_REFUSED"
                        : "STEP_EXECUTION_FAILED", ExecutionPlan.Certainty.NOT_SENT);
            }
        }, simulated, actions);
    }
    public static AsyncNodeAction<AssistantState> observed(NodeAction<AssistantState> node, boolean simulated) {
        return observed(node, simulated, null);
    }
    public static AsyncNodeAction<AssistantState> observed(NodeAction<AssistantState> node, boolean simulated, WorkflowStepActions actions) {
        return node_async(state -> {
            try (var ignored = com.chh.autosense.utils.LogContextUtils.install(com.chh.autosense.utils.LogContextUtils.workflow(state))) {
            var result = node.apply(state);
            if (result.values().stream().anyMatch(org.bsc.async.AsyncGenerator.class::isInstance)) return result;
            if (!simulated || !(result.get(AssistantState.OUTPUT) instanceof AssistantState.OutputContext output))
                return actions == null ? result : actions.afterNode(state, result);
            var data = new java.util.LinkedHashMap<>(output.data());
            var payload = new java.util.LinkedHashMap<String, Object>();
            if (data.get("payload") instanceof java.util.Map<?, ?> values)
                values.forEach((key, value) -> payload.put(key.toString(), value));
            payload.put("simulated", true); data.put("payload", payload);
            var delta = new java.util.LinkedHashMap<>(result);
            delta.put(AssistantState.OUTPUT, new AssistantState.OutputContext(output.type(), "STUB_" + output.code(), output.message(), data));
            return actions == null ? delta : actions.afterNode(state, delta);
            }
        });
    }
    public static boolean failed(AssistantState state) { return state.workflow().status().terminal()
            && state.workflow().status() != com.chh.autosense.domain.enums.WorkflowStatus.COMPLETED; }
}
