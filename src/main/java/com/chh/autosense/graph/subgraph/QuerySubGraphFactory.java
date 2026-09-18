package com.chh.autosense.graph.subgraph;

import com.chh.autosense.config.GraphProperties;
import com.chh.autosense.domain.enums.WorkflowStatus;
import com.chh.autosense.graph.node.*;
import com.chh.autosense.graph.state.AssistantState;
import org.bsc.langgraph4j.*;
import java.time.Clock;
import java.util.Map;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static com.chh.autosense.graph.node.NodeGuard.*;

public final class QuerySubGraphFactory {
    private final WorkflowStepActions actions;
    private final DeviceStepNodes nodes;
    private final StepAttemptExecutor executor;
    private final boolean simulated;
    public QuerySubGraphFactory(WorkflowStepActions actions, GraphProperties properties, Clock clock) {
        this.actions = actions; nodes = new DeviceStepNodes(actions, properties, clock); executor = new StepAttemptExecutor(properties, clock);
        simulated = properties.mode().equals("stub");
    }
    public StateGraph<AssistantState> build() throws GraphStateException {
        var graph = new StateGraph<>(AssistantState.schema(), AssistantState::new);
        graph.addNode("ResolveTarget", guarded(nodes::resolve))
                .addNode("PrepareInput", guarded(new PrepareInput()::apply))
                .addNode("AwaitInput", guarded(new AwaitInput()::apply))
                .addNode("ValidateQuery", guarded(nodes::validate))
                .addNode("PrepareApproval", guarded(nodes::prepareApproval))
                .addNode("AwaitApproval", guarded(nodes::awaitApproval))
                .addNode("Revalidate", guarded(nodes::revalidate))
                .addNode("ReadDevice", guarded(s -> executor.execute(s, d -> {
                    if (!nodes.approved(s)) throw new SecurityException("Approval is no longer valid");
                    actions.revalidate(s); return actions.query(s, d);
                }, true)))
                .addNode("RetryWait", guarded(executor::waitForRetry))
                .addNode("SaveQueryResult", guarded(s -> { actions.commitResult(s, s.plan().results().get(s.plan().step().stepId())); return Map.of(); }));
        graph.addEdge(StateGraph.START, "ResolveTarget");
        graph.addConditionalEdges("ResolveTarget", edge_async(s -> failed(s) ? "end"
                        : s.workflow().status() == WorkflowStatus.WAITING_INPUT ? "input" : "validate"),
                Map.of("end", StateGraph.END, "input", "PrepareInput", "validate", "ValidateQuery"));
        graph.addEdge("PrepareInput", "AwaitInput");
        graph.addConditionalEdges("AwaitInput", edge_async(s -> s.workflow().status() == WorkflowStatus.WAITING_INPUT ? "wait" : "resolve"),
                Map.of("wait", "PrepareInput", "resolve", "ResolveTarget"));
        graph.addConditionalEdges("ValidateQuery", edge_async(s -> failed(s) ? "end" : nodes.approved(s) ? "run" : "wait"),
                Map.of("end", StateGraph.END, "run", "Revalidate", "wait", "PrepareApproval"));
        graph.addConditionalEdges("PrepareApproval", edge_async(s -> failed(s) ? "end" : nodes.approved(s) ? "run" : "wait"),
                Map.of("end", StateGraph.END, "run", "Revalidate", "wait", "AwaitApproval"));
        graph.addConditionalEdges("AwaitApproval", edge_async(s -> failed(s) ? "end" : nodes.approved(s) ? "run" : "wait"),
                Map.of("end", StateGraph.END, "run", "Revalidate", "wait", "PrepareApproval"));
        graph.addConditionalEdges("Revalidate", edge_async(s -> failed(s) ? "end" : nodes.approved(s) ? "run" : "wait"),
                Map.of("end", StateGraph.END, "run", "ReadDevice", "wait", "PrepareApproval"));
        graph.addConditionalEdges("ReadDevice", edge_async(s -> failed(s) ? "end" : s.workflow().status() == WorkflowStatus.RETRYING ? "retry" : "save"),
                Map.of("end", StateGraph.END, "retry", "RetryWait", "save", "SaveQueryResult"));
        next(graph, "RetryWait", "Revalidate"); graph.addEdge("SaveQueryResult", StateGraph.END);
        return graph;
    }
    public static void next(StateGraph<AssistantState> graph, String from, String to) throws GraphStateException {
        graph.addConditionalEdges(from, edge_async(s -> failed(s) ? "end" : "next"), Map.of("end", StateGraph.END, "next", to));
    }
    private org.bsc.langgraph4j.action.AsyncNodeAction<AssistantState> guarded(org.bsc.langgraph4j.action.NodeAction<AssistantState> node) {
        return NodeGuard.guarded(node, simulated, actions);
    }
}
