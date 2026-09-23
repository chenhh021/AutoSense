package com.chh.autosense.graph.node;

import com.chh.autosense.graph.state.AssistantState;
import com.chh.autosense.graph.state.ExecutionPlan;
import com.chh.autosense.utils.LogSanitizer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import java.util.*;

/** Bounded plan diagnostics. Free-form instructions, prompts and device inventories stay out of logs. */
@Slf4j
public final class WorkflowPlanLog {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_ITEMS = 32;
    private WorkflowPlanLog() { }

    public static void generated(AssistantState state, WorkflowStepActions.PlanProposal proposal) {
        var devices = proposal.deviceContext() == null
                ? state.<AssistantState.DeviceContext>value(AssistantState.DEVICE).orElseThrow() : proposal.deviceContext();
        log.info("Workflow plan generated: requestId={}, plannerAttempt={}, outcome={}, stepCount={}, deviceContextInitialized={}, deviceCount={}, snapshotHash={}, clarificationRequired={}",
                state.request().requestId(), state.retry().retriesUsed() + 1, LogSanitizer.label(proposal.outcome()),
                proposal.steps().size(), devices.initialized(), devices.planningDevices().size(), devices.snapshotHash(),
                "CLARIFY".equals(proposal.outcome()));
        for (int index = 0; index < Math.min(proposal.steps().size(), MAX_ITEMS); index++) {
            var step = proposal.steps().get(index);
            var parameters = new LinkedHashMap<String, Object>();
            for (String key : List.of("deviceRef", "brightness", "power", "colorTemperature"))
                if (step.parameters().containsKey(key)) parameters.put(key, scalar(step.parameters().get(key)));
            for (String key : List.of("action", "answerMode"))
                if (step.parameters().containsKey(key)) parameters.put(key, label(step.parameters().get(key)));
            if (step.parameters().get("fields") instanceof List<?> fields)
                parameters.put("fields", fields.stream().limit(100).map(WorkflowPlanLog::label).toList());
            var bindings = new TreeMap<String, Object>();
            step.inputBindings().entrySet().stream().limit(MAX_ITEMS)
                    .forEach(entry -> bindings.put(label(entry.getKey()), reference(entry.getValue())));
            log.info("Workflow plan step: requestId={}, plannerAttempt={}, order={}, stepId={}, type={}, parameters={}, dependsOn={}, inputBindings={}, condition={}, requiresKnowledgeBase={}, diagnosisMode={}",
                    state.request().requestId(), state.retry().retriesUsed() + 1, index + 1, label(step.stepId()),
                    step.type(), encode(parameters), encode(step.dependsOn().stream().limit(MAX_ITEMS).map(WorkflowPlanLog::label).toList()),
                    encode(bindings), encode(condition(step.condition(), 0)), step.requiresKnowledgeBase(), label(step.diagnosisMode()));
        }
        if (proposal.steps().size() > MAX_ITEMS)
            log.warn("Workflow plan log truncated: requestId={}, omittedSteps={}", state.request().requestId(), proposal.steps().size() - MAX_ITEMS);
    }

    private static Object condition(ExecutionPlan.Condition value, int depth) {
        if (value == null) return null;
        if (depth >= 4) return "TRUNCATED";
        var summary = new LinkedHashMap<String, Object>();
        summary.put("op", label(value.op()));
        summary.put("left", operand(value.left()));
        summary.put("right", operand(value.right()));
        summary.put("arguments", value.arguments().stream().limit(8).map(child -> condition(child, depth + 1)).toList());
        return summary;
    }

    private static Object operand(ExecutionPlan.Operand value) {
        return value == null ? null : value.reference() == null ? scalar(value.literal()) : reference(value.reference());
    }

    private static Object reference(ExecutionPlan.Reference value) {
        return value == null ? null : Map.of("stepId", label(value.stepId()), "field", label(value.field()));
    }

    private static Object scalar(Object value) {
        return value instanceof Number || value instanceof Boolean ? value : "REDACTED";
    }

    private static String label(Object value) {
        return value instanceof String text ? LogSanitizer.label(text) : "unknown";
    }

    private static String encode(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (JsonProcessingException e) { return "UNAVAILABLE"; }
    }
}
