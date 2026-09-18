package com.chh.autosense.graph.node;

import com.chh.autosense.domain.enums.WorkflowStatus;
import com.chh.autosense.graph.state.AssistantState;
import java.util.*;
import static com.chh.autosense.graph.state.AssistantState.*;

public final class PrepareInput {
    public Map<String, Object> apply(AssistantState state) {
        String id = UUID.randomUUID().toString();
        String prompt = state.plan().clarifyQuestion().isBlank() ? "请补充设备和问题信息。" : state.plan().clarifyQuestion();
        var delta = GraphUpdates.event(state, WorkflowStatus.WAITING_INPUT, "CLARIFY", "WAITING_INPUT", prompt,
                Map.of("inputRequestId", id));
        var old = (WorkflowContext) delta.get(WORKFLOW);
        delta.put(WORKFLOW, new WorkflowContext(old.status(), old.currentStep(), old.progress(), old.version(), id, prompt,
                state.plan().executionPlan().hash().isBlank() ? "IntentPlanner" : "PlanRouter", "",
                old.schemaVersion(), old.graphVersion(), old.lastOutputSequence(), old.executionFence()));
        var cache = new LinkedHashMap<>(state.retry().completedCalls()); cache.remove("sliceDeadline");
        delta.put(RETRY, new RetryContext(state.retry().retriesUsed(), "", state.retry().latestFailure(), null, cache));
        return delta;
    }
}
