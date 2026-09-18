package com.chh.autosense.graph.node;

import com.chh.autosense.config.GraphProperties;
import com.chh.autosense.domain.enums.WorkflowStatus;
import com.chh.autosense.graph.state.*;
import java.time.Duration;
import java.util.*;
import static com.chh.autosense.graph.state.AssistantState.*;

public final class IntentPlanner {
    private final WorkflowStepActions actions;
    private final StepAttemptExecutor executor;
    public IntentPlanner(WorkflowStepActions actions, StepAttemptExecutor executor) { this.actions = actions; this.executor = executor; }
    public Map<String, Object> apply(AssistantState state) throws Exception {
        return executor.plan(state, remaining -> actions.plan(state, remaining));
    }
}
