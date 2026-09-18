package com.chh.autosense.domain.vo;

import com.chh.autosense.domain.enums.PlanStepType;
import com.chh.autosense.domain.enums.WorkflowStatus;
import com.chh.autosense.graph.state.AssistantState.Progress;
import com.chh.autosense.graph.state.ExecutionPlan.Certainty;
import com.chh.autosense.graph.state.ExecutionPlan.StepStatus;
import com.chh.autosense.graph.state.StateData;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record WorkflowView(String requestId, long conversationId, WorkflowStatus status,
                           int currentStep, Progress progress, long version, boolean canResume,
                           List<StepView> steps, ApprovalView approval, String failureCode,
                           String inputRequestId, String prompt) {
    public WorkflowView { steps = steps == null ? List.of() : List.copyOf(steps); }
    public record StepView(String stepId, PlanStepType type, StepStatus status,
                           Map<String, Object> result, String failureCode,
                           String commandExecutionId, Certainty resultCertainty, int retriesUsed) {
        public StepView { result = StateData.freeze(result); }
    }
    public record ApprovalView(String approvalId, String stepId, String prompt,
                               Instant expiresAt, String operation, Map<String, Object> parameters) {
        public ApprovalView { parameters = StateData.freeze(parameters); }
    }
}
