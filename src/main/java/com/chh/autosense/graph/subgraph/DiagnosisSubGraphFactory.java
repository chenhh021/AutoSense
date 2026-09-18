package com.chh.autosense.graph.subgraph;

import com.chh.autosense.config.GraphProperties;
import com.chh.autosense.domain.enums.WorkflowStatus;
import com.chh.autosense.graph.node.*;
import com.chh.autosense.graph.state.AssistantState;
import org.bsc.langgraph4j.*;
import java.time.Clock;
import java.util.*;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static com.chh.autosense.graph.node.NodeGuard.*;
import static com.chh.autosense.graph.subgraph.QuerySubGraphFactory.next;

public final class DiagnosisSubGraphFactory {
    private final WorkflowStepActions actions;
    private final StepAttemptExecutor executor;
    private final boolean simulated;
    public DiagnosisSubGraphFactory(WorkflowStepActions actions, GraphProperties properties, Clock clock) {
        this.actions = actions; executor = new StepAttemptExecutor(properties, clock);
        simulated = properties.mode().equals("stub");
    }
    public StateGraph<AssistantState> build() throws GraphStateException {
        var graph = new StateGraph<>(AssistantState.schema(), AssistantState::new);
        graph.addNode("PrepareEvidence", guarded(s -> Map.of(AssistantState.DIAGNOSIS, new AssistantState.DiagnosisContext(
                        s.plan().runtimeInputs(), s.plan().step().dependsOn(), Map.of(), Map.of()))))
                .addNode("AnalyzeDiagnosis", guarded(s -> executor.execute(s, d -> actions.diagnose(s, d), true)))
                .addNode("RetryWait", guarded(executor::waitForRetry))
                .addNode("SaveDiagnosisResult", guarded(s -> { actions.commitResult(s, s.plan().results().get(s.plan().step().stepId())); return Map.of(); }));
        // Retrieval and reasoning share one deadline; completed subcalls are cached across timeout retries.
        graph.addEdge(StateGraph.START, "PrepareEvidence"); next(graph, "PrepareEvidence", "AnalyzeDiagnosis");
        graph.addConditionalEdges("AnalyzeDiagnosis", edge_async(s -> failed(s) ? "end" : s.workflow().status() == WorkflowStatus.RETRYING ? "retry" : "save"),
                Map.of("end", StateGraph.END, "retry", "RetryWait", "save", "SaveDiagnosisResult"));
        next(graph, "RetryWait", "AnalyzeDiagnosis"); graph.addEdge("SaveDiagnosisResult", StateGraph.END);
        return graph;
    }
    private org.bsc.langgraph4j.action.AsyncNodeAction<AssistantState> guarded(org.bsc.langgraph4j.action.NodeAction<AssistantState> node) {
        return NodeGuard.guarded(node, simulated, actions);
    }
}
