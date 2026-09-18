package com.chh.autosense.graph.state;

import com.chh.autosense.domain.enums.PlanStepType;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/** Published definition; runtime status is kept separately from model instructions. */
public record ExecutionPlan(List<Step> steps, String hash) implements Serializable {
    public ExecutionPlan {
        steps = steps == null ? List.of() : List.copyOf(steps);
        hash = hash == null ? "" : hash;
    }
    public static ExecutionPlan empty() { return new ExecutionPlan(List.of(), ""); }

    public record Step(String stepId, PlanStepType type, String instruction, String targetHint,
                       Map<String, Object> parameters, List<String> dependsOn,
                       Map<String, Reference> inputBindings, Condition condition,
                       Boolean requiresKnowledgeBase, String diagnosisMode) implements Serializable {
        public Step {
            parameters = StateData.freeze(parameters);
            dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
            inputBindings = inputBindings == null ? Map.of() : Map.copyOf(inputBindings);
        }
    }

    public record Reference(String stepId, String field) implements Serializable {
        @com.fasterxml.jackson.annotation.JsonAnySetter public void rejectUnknown(String key, Object value) {
            throw new IllegalArgumentException("Unexpected reference field");
        }
    }
    public record Operand(Reference reference, Object literal) implements Serializable {
        public Operand { literal = StateData.freezeValue(literal); }
    }
    public record Condition(String op, Operand left, Operand right,
                            List<Condition> arguments) implements Serializable {
        public Condition { arguments = arguments == null ? List.of() : List.copyOf(arguments); }
    }

    public enum StepStatus { PENDING, RUNNING, WAITING_APPROVAL, RETRYING, COMPLETED,
        SKIPPED, FAILED, REJECTED, NOT_EXECUTED }
    public enum Certainty { NOT_SENT, IN_FLIGHT, SUCCEEDED, FAILED, UNKNOWN }

    public record Result(StepStatus status, Map<String, Object> data, String failureCode,
                         Certainty certainty, int retriesUsed) implements Serializable {
        public Result { data = StateData.freeze(data); }
        public boolean successful() { return status == StepStatus.COMPLETED; }
    }
}
