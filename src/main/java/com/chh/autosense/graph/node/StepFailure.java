package com.chh.autosense.graph.node;

import com.chh.autosense.graph.state.ExecutionPlan;

/** Safe business code and effect certainty; never carries provider response bodies. */
public final class StepFailure extends RuntimeException {
    private final String code;
    private final ExecutionPlan.Certainty certainty;
    public StepFailure(String code, ExecutionPlan.Certainty certainty) {
        super(code); this.code = code; this.certainty = certainty;
    }
    public String code() { return code; }
    public ExecutionPlan.Certainty certainty() { return certainty; }
}
