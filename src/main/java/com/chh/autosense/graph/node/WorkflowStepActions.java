package com.chh.autosense.graph.node;

import com.chh.autosense.graph.state.AssistantState;
import com.chh.autosense.graph.state.ExecutionPlan;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Business calls and commit boundaries. Only graph edges decide what runs next. */
public interface WorkflowStepActions {
    default boolean streamsAnswers() { return false; }
    record PlanProposal(String outcome, List<ExecutionPlan.Step> steps, String clarifyQuestion,
                        AssistantState.DeviceContext deviceContext) {
        public PlanProposal { steps = steps == null ? List.of() : List.copyOf(steps); }
        public PlanProposal(String outcome, List<ExecutionPlan.Step> steps, String clarifyQuestion) {
            this(outcome, steps, clarifyQuestion, null);
        }
    }

    PlanProposal plan(AssistantState state, Duration remaining) throws Exception;
    Map<String, Object> resolveTarget(AssistantState state) throws Exception;
    void revalidate(AssistantState state) throws Exception;
    Map<String, Object> answer(AssistantState state, Duration remaining) throws Exception;
    Map<String, Object> query(AssistantState state, Duration remaining) throws Exception;
    Map<String, Object> diagnose(AssistantState state, Duration remaining) throws Exception;
    Map<String, Object> control(AssistantState state, Duration remaining) throws Exception;
    void prepareCommand(AssistantState state) throws Exception;
    void commitResult(AssistantState state, ExecutionPlan.Result result) throws Exception;
    boolean supportsSafeCommandRetry();
    default Map<String, Object> afterNode(AssistantState before, Map<String, Object> delta) { return delta; }
}
