package com.chh.autosense.ai.model;

import com.chh.autosense.ai.model.enums.PlanOutcome;
import com.chh.autosense.domain.enums.PlanStepType;
import com.chh.autosense.graph.node.WorkflowStepActions.PlanProposal;
import com.chh.autosense.graph.state.ExecutionPlan;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonAnySetter;

/** Untrusted model output. Strict decoding retains the security boundary for extra fields. */
public record ExecutionPlanCandidate(PlanOutcome outcome, List<Step> steps, String clarifyQuestion) {
    @JsonAnySetter public void rejectUnknown(String key, Object value) { throw new IllegalArgumentException("Unexpected planner field"); }
    public record Step(String stepId, String type, String instruction, String targetHint,
                       Map<String, Object> parameters, List<String> dependsOn,
                       Map<String, ExecutionPlan.Reference> inputBindings, Condition condition,
                       Boolean requiresKnowledgeBase, String diagnosisMode) {
        @JsonAnySetter public void rejectUnknown(String key, Object value) { throw new IllegalArgumentException("Unexpected plan step field"); }
    }
    public record Condition(String op, Object left, Object right, List<Condition> arguments) {
        @JsonAnySetter public void rejectUnknown(String key, Object value) { throw new IllegalArgumentException("Unexpected condition field"); }
        ExecutionPlan.Condition expression() {
            return new ExecutionPlan.Condition(op, operand(left), operand(right), arguments == null ? List.of() : arguments.stream().map(Condition::expression).toList());
        }
        private static ExecutionPlan.Operand operand(Object value) {
            if (value == null) return null;
            if (value instanceof Map<?, ?> reference) {
                if (!reference.keySet().equals(java.util.Set.of("stepId", "field"))
                        || !(reference.get("stepId") instanceof String stepId) || !(reference.get("field") instanceof String field))
                    throw new IllegalArgumentException("Invalid condition reference");
                return new ExecutionPlan.Operand(new ExecutionPlan.Reference(stepId, field), null);
            }
            if (!(value instanceof Number || value instanceof String || value instanceof Boolean))
                throw new IllegalArgumentException("Invalid condition literal");
            return new ExecutionPlan.Operand(null, value);
        }
    }

    public static ExecutionPlanCandidate decode(String json) throws java.io.IOException {
        return new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue(json, ExecutionPlanCandidate.class);
    }

    public PlanProposal proposal() {
        if (outcome == null) throw new IllegalArgumentException("Missing plan outcome");
        return new PlanProposal(outcome.name(), steps == null ? List.of() : steps.stream().map(s -> {
            PlanStepType type;
            try { type = PlanStepType.valueOf(s.type()); }
            catch (IllegalArgumentException | NullPointerException e) { type = null; }
            return new ExecutionPlan.Step(s.stepId(), type, s.instruction(), s.targetHint(), s.parameters(),
                    s.dependsOn(), s.inputBindings(), s.condition() == null ? null : s.condition().expression(), s.requiresKnowledgeBase(), s.diagnosisMode());
        }).toList(), clarifyQuestion);
    }
}
