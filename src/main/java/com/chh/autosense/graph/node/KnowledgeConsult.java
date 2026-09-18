package com.chh.autosense.graph.node;

import com.chh.autosense.graph.state.AssistantState;
import java.util.Map;

public final class KnowledgeConsult {
    private final WorkflowStepActions actions;
    private final StepAttemptExecutor executor;
    public KnowledgeConsult(WorkflowStepActions actions, StepAttemptExecutor executor) { this.actions = actions; this.executor = executor; }
    public Map<String, Object> apply(AssistantState state) {
        if (actions.streamsAnswers()) return Map.of("answerStream", AiTokenStreamAdapter.embed(state, "KnowledgeConsult",
                () -> executor.execute(state, remaining -> actions.answer(state, remaining), true), actions));
        return executor.execute(state, remaining -> actions.answer(state, remaining), true);
    }
}
