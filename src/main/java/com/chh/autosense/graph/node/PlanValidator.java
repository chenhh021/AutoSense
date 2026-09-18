package com.chh.autosense.graph.node;

import com.chh.autosense.config.GraphProperties;
import com.chh.autosense.domain.enums.PlanStepType;
import com.chh.autosense.graph.state.ExecutionPlan;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Pure structural validation: never resolves ownership or calls a device. */
public final class PlanValidator {
    private static final Set<String> PUBLIC_FIELDS = Set.of("deviceRef", "deviceType", "model", "brightness",
            "power", "colorTemperature", "temperature", "online", "state", "evidence", "diagnosis", "proposal", "answer");
    private static final Map<PlanStepType, Set<String>> PARAMETERS = Map.of(
            PlanStepType.KNOWLEDGE_CONSULT, Set.of("deviceType", "model", "question"),
            PlanStepType.DEVICE_QUERY, Set.of("deviceRef", "deviceType", "model", "fields", "action"),
            PlanStepType.FAULT_DIAGNOSIS, Set.of("deviceRef", "deviceType", "model", "symptoms", "evidence"),
            PlanStepType.DEVICE_CONTROL, Set.of("deviceRef", "deviceType", "model", "action", "brightness", "power", "colorTemperature"));
    private final GraphProperties properties;
    public PlanValidator(GraphProperties properties) { this.properties = properties; }

    public static final class ClarificationRequired extends IllegalArgumentException {
        public ClarificationRequired() { super("Plan needs clarification"); }
    }

    public ExecutionPlan validate(WorkflowStepActions.PlanProposal proposal) {
        require(proposal != null, "Missing plan");
        String outcome = proposal.outcome();
        require(Set.of("PLAN", "CLARIFY", "OUT_OF_SCOPE").contains(Objects.toString(outcome, "")), "Invalid outcome");
        if (!outcome.equals("PLAN")) {
            require(proposal.steps().isEmpty(), "Non-executable outcome has steps");
            if (outcome.equals("CLARIFY")) require(proposal.clarifyQuestion() != null
                    && !proposal.clarifyQuestion().isBlank(), "Missing clarification");
            return ExecutionPlan.empty();
        }
        require(!proposal.steps().isEmpty() && proposal.steps().size() <= properties.maxPlanSteps(), "Invalid step count");
        Set<String> prior = new HashSet<>();
        for (var step : proposal.steps()) {
            require(step.stepId() != null && step.stepId().matches("[A-Za-z][A-Za-z0-9_-]{0,63}")
                    && !prior.contains(step.stepId()), "Invalid step identity");
            if (step.type() == null || (step.type() == PlanStepType.KNOWLEDGE_CONSULT
                    && step.requiresKnowledgeBase() == null)) throw new ClarificationRequired();
            require(step.instruction() != null && !step.instruction().isBlank() && step.instruction().length() <= 4096,
                    "Invalid instruction");
            require(step.type() == PlanStepType.KNOWLEDGE_CONSULT || step.requiresKnowledgeBase() == null,
                    "Unexpected knowledge flag");
            require(step.diagnosisMode() == null || (step.type() == PlanStepType.FAULT_DIAGNOSIS
                    && Set.of("DEFAULT", "AFTERSALES").contains(step.diagnosisMode())), "Invalid diagnosis mode");
            require(new HashSet<>(step.dependsOn()).size() == step.dependsOn().size()
                    && prior.containsAll(step.dependsOn()), "Invalid dependency");
            require(PARAMETERS.get(step.type()).containsAll(step.parameters().keySet())
                    && PARAMETERS.get(step.type()).containsAll(step.inputBindings().keySet()), "Unsafe parameter");
            step.parameters().forEach(this::parameter);
            if (step.parameters().get("action") instanceof String action) require(
                    step.type() == PlanStepType.DEVICE_QUERY ? Set.of("list", "state", "diagnostic_snapshot").contains(action)
                            : step.type() == PlanStepType.DEVICE_CONTROL && Set.of("setBrightness", "setPower", "setColorTemperature").contains(action),
                    "Action does not match step type");
            step.inputBindings().values().forEach(ref -> reference(ref, prior, step.dependsOn()));
            condition(step.condition(), prior, step.dependsOn(), 0);
            prior.add(step.stepId());
        }
        return new ExecutionPlan(proposal.steps(), digest(proposal.steps()));
    }

    private void parameter(String name, Object value) {
        require(value != null, "Missing parameter value");
        switch (name) {
            case "fields" -> require(value instanceof List<?> fields && !fields.isEmpty()
                    && fields.stream().allMatch(PUBLIC_FIELDS::contains), "Invalid query fields");
            case "action" -> require(value instanceof String && Set.of("list", "state", "diagnostic_snapshot",
                    "setBrightness", "setPower", "setColorTemperature").contains(value), "Unknown action");
            case "brightness" -> require(value instanceof Number number && number.doubleValue() >= 0
                    && number.doubleValue() <= 100, "Invalid brightness");
            case "colorTemperature" -> require(value instanceof Number number && number.doubleValue() >= 1000
                    && number.doubleValue() <= 10000, "Invalid color temperature");
            case "power" -> require(value instanceof Boolean, "Invalid power");
            case "deviceRef" -> require(value instanceof Number n && Double.isFinite(n.doubleValue()) && n.doubleValue() == n.longValue() && n.longValue() > 0, "Invalid device reference");
            default -> require(value instanceof String text && text.length() <= 4096, "Invalid parameter shape");
        }
    }

    private void condition(ExecutionPlan.Condition value, Set<String> prior, List<String> dependencies, int depth) {
        if (value == null) return;
        require(depth < 4, "Condition is too deep");
        require(Set.of("EQ", "NE", "LT", "LE", "GT", "GE", "AND", "OR")
                .contains(Objects.toString(value.op(), "")), "Invalid condition operator");
        if (value.op().equals("AND") || value.op().equals("OR")) {
            require(value.left() == null && value.right() == null && value.arguments().size() >= 2
                    && value.arguments().size() <= 8, "Invalid boolean expression");
            value.arguments().forEach(c -> { require(c != null, "Missing condition"); condition(c, prior, dependencies, depth + 1); });
        } else {
            require(value.arguments().isEmpty(), "Unexpected condition arguments");
            operand(value.left(), prior, dependencies); operand(value.right(), prior, dependencies);
        }
    }

    private void operand(ExecutionPlan.Operand operand, Set<String> prior, List<String> dependencies) {
        require(operand != null && (operand.reference() == null) != (operand.literal() == null), "Invalid operand");
        if (operand.reference() != null) reference(operand.reference(), prior, dependencies);
        else require(operand.literal() instanceof String || operand.literal() instanceof Number
                || operand.literal() instanceof Boolean, "Invalid literal");
    }

    private void reference(ExecutionPlan.Reference ref, Set<String> prior, List<String> dependencies) {
        require(ref != null && prior.contains(ref.stepId()) && dependencies.contains(ref.stepId())
                && PUBLIC_FIELDS.contains(ref.field()), "Invalid result reference");
    }

    public static String digest(Object value) {
        try {
            var mapper = new ObjectMapper();
            Object tree = mapper.convertValue(value, Object.class);
            String canonical = mapper.writeValueAsString(sorted(tree));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalArgumentException("Cannot hash plan data", e); }
    }

    private static Object sorted(Object value) {
        if (value instanceof Map<?, ?> map) {
            var ordered = new TreeMap<String, Object>();
            map.forEach((key, item) -> ordered.put(key.toString(), sorted(item)));
            return ordered;
        }
        if (value instanceof List<?> list) return list.stream().map(PlanValidator::sorted).toList();
        return value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
