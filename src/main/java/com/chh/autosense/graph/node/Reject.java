package com.chh.autosense.graph.node;

import com.chh.autosense.domain.enums.WorkflowStatus;
import com.chh.autosense.graph.state.*;
import java.util.*;
import static com.chh.autosense.graph.state.AssistantState.*;

public final class Reject {
    private final WorkflowStepActions actions;
    public Reject(WorkflowStepActions actions) { this.actions = actions; }
    public Map<String, Object> apply(AssistantState state) throws Exception {
        var plan = state.plan(); var results = new LinkedHashMap<>(plan.results());
        if (plan.currentStep() < plan.executionPlan().steps().size()) {
            var result = results.get(plan.step().stepId());
            if (result != null) actions.commitResult(state, result);
            for (int i = plan.currentStep() + 1; i < plan.executionPlan().steps().size(); i++) {
                results.putIfAbsent(plan.executionPlan().steps().get(i).stepId(), new ExecutionPlan.Result(
                        ExecutionPlan.StepStatus.NOT_EXECUTED, Map.of(), "PLAN_STOPPED", ExecutionPlan.Certainty.NOT_SENT, 0));
            }
        }
        var delta = new LinkedHashMap<String, Object>();
        delta.put(PLAN, new PlanContext(plan.executionPlan(), plan.currentStep(), plan.runtimeInputs(), results,
                plan.candidateOutcome(), plan.clarifyQuestion()));
        var status = state.workflow().status() == WorkflowStatus.CANCELLED ? WorkflowStatus.CANCELLED
                : state.workflow().status() == WorkflowStatus.REJECTED ? WorkflowStatus.REJECTED : WorkflowStatus.FAILED;
        delta.putAll(GraphUpdates.event(GraphUpdates.apply(state, delta), status, "ERROR", state.workflow().failureCode(),
                "本次计划已停止，已完成的结果仍然保留。", Map.of("failureCode", state.workflow().failureCode())));
        return delta;
    }
}
