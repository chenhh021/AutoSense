package com.chh.autosense.core.security;

import com.chh.autosense.graph.state.AssistantState;
import java.time.Instant;
import java.util.Objects;

/** Server-only handle. No public constructor, JSON binding or client approval flag. */
public final class DeviceToolExecutionContext {
    public enum Phase { APPROVED_QUERY }
    private final AssistantState state;
    private final Phase phase;
    private final Instant deadline;
    private final java.util.List<AssistantState> approvedStates;

    DeviceToolExecutionContext(AssistantState state, Phase phase, Instant deadline) {
        this(java.util.List.of(state), phase, deadline);
    }
    DeviceToolExecutionContext(java.util.List<AssistantState> states, Phase phase, Instant deadline) {
        if (states.isEmpty()) throw new IllegalArgumentException("Missing execution scopes");
        this.approvedStates = states.stream().map(value -> new AssistantState(value.data())).toList();
        AssistantState state = approvedStates.getFirst();
        this.state = new AssistantState(state.data());
        this.phase = Objects.requireNonNull(phase);
        this.deadline = Objects.requireNonNull(deadline);
    }

    AssistantState state() { return state; }
    java.util.List<AssistantState> states() { return approvedStates; }
    DeviceToolExecutionContext forSn(String sn) {
        return approvedStates.stream().filter(value -> Objects.equals(sn,
                value.<AssistantState.DeviceContext>value(AssistantState.DEVICE).orElseThrow().resolved().get("sn")))
                .findFirst().map(value -> new DeviceToolExecutionContext(value, phase, deadline))
                .orElseThrow(() -> new SecurityException("SN is outside approved scope"));
    }
    public Boolean snapshotOnline(String sn) {
        var scope = forSn(sn);
        var snapshot = scope.state.<AssistantState.DeviceContext>value(AssistantState.DEVICE).orElseThrow();
        if (!snapshot.initialized()) return null;
        return snapshot.planningDevices().stream().filter(device -> Objects.equals(sn, device.sn()))
                .findFirst().map(device -> device.online()).orElse(false);
    }
    public String invocationKey(String sn) { var scope = forSn(sn); return scope.requestId() + ":" + scope.stepId(); }
    public Phase phase() { return phase; }
    public Instant deadline() { return deadline; }
    public AuthUser actor() { return new AuthUser(state.request().userId()); }
    public String requestId() { return state.request().requestId(); }
    public String stepId() { return state.plan().step().stepId(); }
    public long fence() { return state.workflow().executionFence(); }
}
