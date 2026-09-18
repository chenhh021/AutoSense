package com.chh.autosense.domain.message;

import com.chh.autosense.domain.enums.PlanStepType;
import com.chh.autosense.domain.enums.WorkflowStatus;
import com.chh.autosense.graph.state.AssistantState.Progress;
import com.chh.autosense.graph.state.StateData;
import java.util.Map;

/** Public stream envelope; never serializes AgentState or an audit entity. */
public record WorkflowEvent(String type, String code, String message, Data data) {
    public record Data(String eventId, long sequence, String requestId, long conversationId,
                       String stepId, PlanStepType stepType, WorkflowStatus status,
                       Progress progress, long version, Map<String, Object> payload) {
        public Data { payload = StateData.freeze(payload); }
    }
}
