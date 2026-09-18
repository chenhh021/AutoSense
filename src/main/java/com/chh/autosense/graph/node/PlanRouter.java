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
        return operand.reference() == null ? operand.literal() : resolve(operand.reference(), state);
    }

    private Object resolve(ExecutionPlan.Reference reference, AssistantState state) {
        var result = state.plan().results().get(reference.stepId());
        if (result == null || !result.successful() || !result.data().containsKey(reference.field())
                || result.data().get(reference.field()) == null) throw new IllegalArgumentException("Missing result evidence");
        return result.data().get(reference.field());
    }
}
