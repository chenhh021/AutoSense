package com.chh.autosense.graph.node;

import com.chh.autosense.domain.enums.PlanStepType;
import com.chh.autosense.graph.state.AssistantState;
import com.chh.autosense.graph.state.ExecutionPlan;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Offline fixtures only. Fault behavior is constructor-injected, never selected by user text. */
public final class StubWorkflowActions implements WorkflowStepActions {
    public enum Fault { NONE, TIMEOUT_ONCE, TIMEOUT_ALWAYS, EXPLICIT_ERROR, REFUSED }
    private final PlanProposal fixture;
    private final Map<String, Fault> faults;
    private final int brightness;
    private final boolean safeRetry;
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> commands = new ConcurrentHashMap<>();
    private final Map<String, ExecutionPlan.Result> results = new ConcurrentHashMap<>();

    public StubWorkflowActions() { this(null, Map.of(), 20, true); }
    public StubWorkflowActions(PlanProposal fixture, Map<String, Fault> faults, int brightness, boolean safeRetry) {
        this.fixture = fixture; this.faults = Map.copyOf(faults); this.brightness = brightness; this.safeRetry = safeRetry;
    }
    public List<String> calls() { return List.copyOf(calls); }
    public int commandCount() { return commands.size(); }

    public static AssistantState.DeviceContext fixtureDevices() {
        return new AssistantState.DeviceContext(Map.of(), Map.of(), List.of(Map.of("id", 1L, "name", "lamp",
                "sn", "stub-sn", "deviceType", "smart_bulb", "deviceModel", "stub-lamp", "online", true)), Map.of(), Map.of(),
                true, java.time.Instant.now().toString(), "stub-device");
    }
    @Override public PlanProposal plan(AssistantState state, Duration remaining) throws Exception {
        call("plan", state);
        var proposal = proposal(state);
        boolean needsDevices = proposal.steps().stream().anyMatch(step -> step.parameters().containsKey("deviceRef")
                || "DEVICE_CONTEXT".equals(step.parameters().get("answerMode")));
        return new PlanProposal(proposal.outcome(), proposal.steps(), proposal.clarifyQuestion(),
                needsDevices ? fixtureDevices() : null);
    }

    private PlanProposal proposal(AssistantState state) {
        if (fixture != null) return fixture;
        String text = Objects.toString(state.plan().runtimeInputs().get("clarification"), state.request().userMessage());
        if (text.contains("低于") && text.contains("调")) {
            var query = step("s1", PlanStepType.DEVICE_QUERY);
            var control = new ExecutionPlan.Step("s2", PlanStepType.DEVICE_CONTROL, "Set brightness", "lamp",
                    Map.of("brightness", 80), List.of("s1"), Map.of("deviceRef", new ExecutionPlan.Reference("s1", "deviceRef")),
                    new ExecutionPlan.Condition("LT", new ExecutionPlan.Operand(new ExecutionPlan.Reference("s1", "brightness"), null),
                            new ExecutionPlan.Operand(null, 30), List.of()), null, null);
            return new PlanProposal("PLAN", List.of(query, control), null);
        }
        if (text.contains("天气") || text.contains("股票")) return new PlanProposal("OUT_OF_SCOPE", List.of(), null);
        if (text.isBlank() || text.contains("帮帮我")) return new PlanProposal("CLARIFY", List.of(), "请说明要咨询或操作的设备及问题。");
        PlanStepType type = text.contains("故障") || text.contains("不亮") ? PlanStepType.FAULT_DIAGNOSIS
                : text.contains("打开") || text.contains("调到") || text.contains("关闭") ? PlanStepType.DEVICE_CONTROL
                : text.contains("查询") || text.contains("状态") ? PlanStepType.DEVICE_QUERY : PlanStepType.KNOWLEDGE_CONSULT;
        return new PlanProposal("PLAN", List.of(step("s1", type)), null);
    }

    public static ExecutionPlan.Step step(String id, PlanStepType type) {
        return new ExecutionPlan.Step(id, type, "Offline fixture", "lamp",
                type == PlanStepType.DEVICE_QUERY || type == PlanStepType.DEVICE_CONTROL ? Map.of("deviceRef", 1L) : Map.of(), List.of(), Map.of(), null,
                type == PlanStepType.KNOWLEDGE_CONSULT ? false : null, null);
    }

    @Override public Map<String, Object> resolveTarget(AssistantState state) {
        return Map.of("deviceRef", 1, "deviceType", "smart_bulb", "model", "stub-lamp");
    }
    @Override public void revalidate(AssistantState state) {
        if (state.request().userId() != 1) throw new SecurityException("Device access denied");
    }
    @Override public Map<String, Object> answer(AssistantState state, Duration remaining) throws Exception {
        call("answer", state); return Map.of("answer", "模拟知识回答", "simulated", true);
    }
    @Override public Map<String, Object> query(AssistantState state, Duration remaining) throws Exception {
        call("query", state); return Map.of("deviceRef", 1, "brightness", brightness, "simulated", true);
    }
    @Override public Map<String, Object> diagnose(AssistantState state, Duration remaining) throws Exception {
        call("diagnose", state); return Map.of("diagnosis", "模拟诊断建议", "simulated", true);
    }
    @Override public Map<String, Object> control(AssistantState state, Duration remaining) throws Exception {
        call("control", state);
        return commands.computeIfAbsent(state.control().idempotencyKey(), key -> Map.of("deviceRef", 1,
                "brightness", 80, "simulated", true, "verified", false));
    }
    @Override public void prepareCommand(AssistantState state) { calls.add("prepareCommand"); }
    @Override public void commitResult(AssistantState state, ExecutionPlan.Result result) {
        String key = state.request().requestId() + ":" + state.plan().step().stepId();
        var prior = results.putIfAbsent(key, result);
        if (prior != null && !prior.equals(result)) throw new IllegalStateException("Conflicting step result");
    }
    @Override public boolean supportsSafeCommandRetry() { return safeRetry; }

    private void call(String operation, AssistantState state) throws Exception {
        calls.add(operation);
        String stepId = state.plan().executionPlan().steps().isEmpty() ? "planner" : state.plan().step().stepId();
        String key = state.request().requestId() + ":" + stepId + ":" + operation;
        int count = attempts.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet();
        Fault fault = faults.getOrDefault(stepId, Fault.NONE);
        if (fault == Fault.TIMEOUT_ALWAYS || (fault == Fault.TIMEOUT_ONCE && count == 1))
            throw new SocketTimeoutException("Simulated request timeout");
        if (fault == Fault.EXPLICIT_ERROR) throw new IllegalStateException("Simulated service error");
        if (fault == Fault.REFUSED) throw new SecurityException("Simulated refusal");
    }
}
