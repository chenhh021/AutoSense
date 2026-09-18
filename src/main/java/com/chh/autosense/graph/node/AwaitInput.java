package com.chh.autosense.graph.node;

import com.chh.autosense.domain.enums.WorkflowStatus;
import com.chh.autosense.graph.state.AssistantState;
import java.util.*;

public final class AwaitInput {
    public Map<String, Object> apply(AssistantState state) {
        Object text = state.plan().runtimeInputs().get("clarification");
        if (!(text instanceof String value) || value.isBlank()) return Map.of();
        return GraphUpdates.event(state, WorkflowStatus.RUNNING, "STATUS", "INPUT_RECEIVED", "已收到补充信息。", Map.of());
    }
}
