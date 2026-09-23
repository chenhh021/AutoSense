package com.chh.autosense.graph.node;

import com.chh.autosense.graph.state.ExecutionPlan;

/** Safe business code and effect certainty; never carries provider response bodies. */
public final class StepFailure extends RuntimeException {
    private final String code;
    private final ExecutionPlan.Certainty certainty;
    private final java.util.Map<String, Object> data;
    public StepFailure(String code, ExecutionPlan.Certainty certainty) {
        this(code, certainty, java.util.Map.of());
    }
    public StepFailure(String code, ExecutionPlan.Certainty certainty, java.util.Map<String, Object> data) {
        super(code); this.code = code; this.certainty = certainty;
        this.data = com.chh.autosense.graph.state.StateData.freeze(data);
    }
    public java.util.Map<String, Object> data() { return data; }
    public String code() { return code; }
    public ExecutionPlan.Certainty certainty() { return certainty; }
}
