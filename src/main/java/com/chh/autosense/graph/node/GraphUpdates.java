package com.chh.autosense.graph.node;

import com.chh.autosense.domain.enums.WorkflowStatus;
import com.chh.autosense.graph.state.AssistantState;
import com.chh.autosense.graph.state.ExecutionPlan;
import java.util.*;
import static com.chh.autosense.graph.state.AssistantState.*;

/** Builds atomic context replacements and an explicitly public output envelope. */
public final class GraphUpdates {
    private GraphUpdates() { }

    public static Map<String, Object> event(AssistantState state, WorkflowStatus status, String type,
                                             String code, String message, Map<String, Object> payload) {
        var old = state.workflow();
        long sequence = old.lastOutputSequence() + 1;
        var workflow = new WorkflowContext(status, state.plan().currentStep(), progress(state.plan()), old.version() + 1,
                old.inputRequestId(), old.prompt(), old.returnNode(), status == WorkflowStatus.FAILED
                || status == WorkflowStatus.REJECTED || status == WorkflowStatus.CANCELLED ? code : "", old.schemaVersion(), old.graphVersion(), sequence, old.executionFence());
        var data = new LinkedHashMap<String, Object>();
        data.put("eventId", state.request().requestId() + ":" + sequence);
        data.put("sequence", sequence); data.put("requestId", state.request().requestId());
        data.put("conversationId", state.request().conversationId()); data.put("status", status.name());
        data.put("version", workflow.version());
        if (state.plan().currentStep() < state.plan().executionPlan().steps().size()) {
            data.put("stepId", state.plan().step().stepId());
            if (state.plan().step().type() != null) data.put("stepType", state.plan().step().type().name());
        }
        data.put("progress", Map.of("total", workflow.progress().total(), "completed", workflow.progress().completed(),
                "skipped", workflow.progress().skipped(), "notExecuted", workflow.progress().notExecuted()));
        data.put("payload", payload);
        var delta = new LinkedHashMap<String, Object>();
        delta.put(WORKFLOW, workflow); delta.put(OUTPUT, new OutputContext(type, code, message, data));
        return delta;
    }

    public static Progress progress(PlanContext plan) {
        return new Progress(plan.executionPlan().steps().size(), count(plan, ExecutionPlan.StepStatus.COMPLETED),
                count(plan, ExecutionPlan.StepStatus.SKIPPED), count(plan, ExecutionPlan.StepStatus.NOT_EXECUTED));
    }
    private static int count(PlanContext plan, ExecutionPlan.StepStatus status) {
        return (int) plan.results().values().stream().filter(r -> r.status() == status).count();
    }
    public static Map<String, Object> result(AssistantState state, ExecutionPlan.Result result) {
        var plan = state.plan(); var results = new LinkedHashMap<>(plan.results());
        results.put(plan.step().stepId(), result);
        return Map.of(PLAN, new PlanContext(plan.executionPlan(), plan.currentStep(), plan.runtimeInputs(), results,
                plan.candidateOutcome(), plan.clarifyQuestion()));
    }
    public static AssistantState apply(AssistantState state, Map<String, Object> delta) {
        var data = new LinkedHashMap<>(state.data()); data.putAll(delta); return new AssistantState(data);
    }
    public static Map<String, Object> failure(AssistantState state, String code, ExecutionPlan.Certainty certainty) {
        return failure(state, code, certainty, Map.of());
    }
    public static Map<String, Object> failure(AssistantState state, String code, ExecutionPlan.Certainty certainty, Map<String, Object> publicData) {
        var delta = new LinkedHashMap<String, Object>();
        if (state.plan().currentStep() < state.plan().executionPlan().steps().size()) {
            delta.putAll(result(state, new ExecutionPlan.Result(ExecutionPlan.StepStatus.FAILED,
                    publicData, code, certainty, state.retry().retriesUsed())));
        }
        var payload = new LinkedHashMap<>(publicData); payload.put("failureCode", code); payload.put("effectCertainty", certainty.name());
        delta.putAll(event(apply(state, delta), WorkflowStatus.FAILED, "STEP_RESULT", code,
                "步骤执行失败：" + code, payload));
        return delta;
    }
}
