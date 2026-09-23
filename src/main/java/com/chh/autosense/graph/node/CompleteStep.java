package com.chh.autosense.graph.node;

import com.chh.autosense.domain.enums.WorkflowStatus;
import com.chh.autosense.graph.state.*;
import java.util.*;
import static com.chh.autosense.graph.state.AssistantState.*;

public final class CompleteStep {
    private final WorkflowStepActions actions;
    public CompleteStep(WorkflowStepActions actions) { this.actions = actions; }
    public Map<String, Object> apply(AssistantState state) throws Exception {
        var plan = state.plan(); var result = plan.results().get(plan.step().stepId());
        if (result == null || !(result.successful() || result.status() == ExecutionPlan.StepStatus.SKIPPED))
            throw new IllegalStateException("Cannot complete an unsuccessful step");
        actions.commitResult(state, result);
        var delta = GraphUpdates.event(state, WorkflowStatus.RUNNING, "STEP_RESULT", result.status().name(),
                result.status() == ExecutionPlan.StepStatus.SKIPPED ? "条件不满足，已跳过。" : "步骤已完成。", result.data());
        int next = plan.currentStep() + 1;
        var advanced = new PlanContext(plan.executionPlan(), next, Map.of(), plan.results(), plan.candidateOutcome(), "");
        var workflow = (WorkflowContext) delta.get(WORKFLOW);
        delta.put(PLAN, advanced);
        delta.put(WORKFLOW, new WorkflowContext(workflow.status(), next, GraphUpdates.progress(advanced), workflow.version(),
                "", "", "", "", workflow.schemaVersion(), workflow.graphVersion(), workflow.lastOutputSequence(), workflow.executionFence()));
        delta.put(CONTROL, new ControlContext(Map.of(), "", false, "", null, "", Map.of()));
        delta.put(DEVICE, state.<DeviceContext>value(DEVICE).orElseThrow().withStep(Map.of(), Map.of()));
        delta.put(DIAGNOSIS, new DiagnosisContext(Map.of(), List.of(), Map.of(), Map.of()));
        // Preserve the active slice deadline across ordinary steps, but not step-local attempts.
        Object deadline = state.retry().completedCalls().get("sliceDeadline");
        delta.put(RETRY, new RetryContext(0, "", "", null, deadline == null ? Map.of() : Map.of("sliceDeadline", deadline)));
        return delta;
    }
}
