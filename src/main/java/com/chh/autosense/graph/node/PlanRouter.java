package com.chh.autosense.graph.node;

import com.chh.autosense.graph.state.AssistantState;
import com.chh.autosense.graph.state.ExecutionPlan;
import java.math.BigDecimal;
import java.util.*;

/** Evaluates a finite expression tree over committed public results only. */
public final class PlanRouter {
    public Map<String, Object> inputs(AssistantState state) {
        var inputs = new LinkedHashMap<>(state.plan().step().parameters());
        state.plan().step().inputBindings().forEach((name, reference) -> inputs.put(name, resolve(reference, state)));
        return inputs;
    }

    public boolean shouldRun(AssistantState state) { return evaluate(state.plan().step().condition(), state); }

    private boolean evaluate(ExecutionPlan.Condition condition, AssistantState state) {
        if (condition == null) return true;
        // Evaluate all operands: missing evidence must not disappear behind short-circuiting.
        if (condition.op().equals("AND") || condition.op().equals("OR")) {
            List<Boolean> values = condition.arguments().stream().map(c -> evaluate(c, state)).toList();
            return condition.op().equals("AND") ? values.stream().allMatch(Boolean::booleanValue)
                    : values.stream().anyMatch(Boolean::booleanValue);
        }
        Object left = value(condition.left(), state), right = value(condition.right(), state);
        int comparison;
        if (left instanceof Number && right instanceof Number) {
            comparison = new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString()));
        } else if ((left instanceof String && right instanceof String)
                || (left instanceof Boolean && right instanceof Boolean)) {
            if (!Set.of("EQ", "NE").contains(condition.op())) throw new IllegalArgumentException("Condition type mismatch");
            comparison = left.equals(right) ? 0 : 1;
        } else throw new IllegalArgumentException("Condition type mismatch");
        return switch (condition.op()) {
            case "EQ" -> comparison == 0;
            case "NE" -> comparison != 0;
            case "LT" -> comparison < 0;
            case "LE" -> comparison <= 0;
            case "GT" -> comparison > 0;
            case "GE" -> comparison >= 0;
            default -> throw new IllegalArgumentException("Unknown condition operator");
        };
    }

    private Object value(ExecutionPlan.Operand operand, AssistantState state) {
        if (operand.reference() != null && state.plan().step().type() == com.chh.autosense.domain.enums.PlanStepType.DEVICE_CONTROL) {
            var result = state.plan().results().get(operand.reference().stepId());
            if (result != null && isMock(result.data())) throw new StepFailure("MOCK_EVIDENCE_NOT_ALLOWED", ExecutionPlan.Certainty.NOT_SENT);
        }
        return operand.reference() == null ? operand.literal() : resolve(operand.reference(), state);
    }

    private Object resolve(ExecutionPlan.Reference reference, AssistantState state) {
        var result = state.plan().results().get(reference.stepId());
        if (result == null || !result.successful()) throw new IllegalArgumentException("Missing result evidence");
        if (state.plan().step().type() == com.chh.autosense.domain.enums.PlanStepType.DEVICE_CONTROL && isMock(result.data())
                && !Set.of("deviceRef", "deviceType", "model").contains(reference.field()))
            throw new StepFailure("MOCK_EVIDENCE_NOT_ALLOWED", ExecutionPlan.Certainty.NOT_SENT);
        return publicValue(result.data(), reference.field());
    }

    public static boolean isMock(Map<String, Object> data) {
        // Historical name retained for callers: reject all untrusted device evidence, including incomplete legacy results.
        if (!data.containsKey("deviceRef") && !data.containsKey("onlineInfo") && !data.containsKey("getResults")) return false;
        return !hasTrustedDeviceEvidence(data);
    }
    public static boolean hasTrustedDeviceEvidence(Map<String, Object> data) {
        Object source = data.get("source");
        return ("REAL".equals(source) || "SIMULATOR".equals(source))
                && data.get("onlineInfo") instanceof Map<?, ?> info && source.equals(info.get("source"))
                && data.get("evidence") instanceof Map<?, ?> evidence && source.equals(evidence.get("source"));
    }
    public static Object publicValue(Map<String, Object> data, String field) {
        if (!field.startsWith("getResults.")) {
            Object value = data.get(field); if (value == null) throw new IllegalArgumentException("Missing result evidence"); return value;
        }
        if (!PlanValidator.dynamicPath(field)) throw new IllegalArgumentException("Invalid result path");
        Object value = data.get("onlineInfo");
        for (String key : field.split("\\.")) {
            if (!(value instanceof Map<?, ?> map) || !map.containsKey(key)) throw new IllegalArgumentException("Missing model property");
            value = map.get(key);
        }
        if (value == null) throw new IllegalArgumentException("Missing model property");
        if (!(data.get("capabilityInfo") instanceof Map<?, ?> capability)
                || !(capability.get("rawJson") instanceof String raw)
                || !(capability.get("deviceType") instanceof String type)
                || !(capability.get("deviceModel") instanceof String model))
            throw new IllegalArgumentException("Missing source model definition");
        var definition = com.chh.autosense.service.impl.DeviceCapabilityServiceImpl.parse(type, model, raw);
        if (!definition.contentHash().equals(capability.get("contentHash"))) throw new IllegalArgumentException("Invalid source model hash");
        definition.field(field).validate(new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(value));
        return value;
    }
}
