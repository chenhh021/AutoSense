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
import static com.chh.autosense.graph.subgraph.QuerySubGraphFactory.next;

public final class ControlSubGraphFactory {
    private final WorkflowStepActions actions;
    private final DeviceStepNodes nodes;
    private final StepAttemptExecutor executor;
    private final boolean simulated;
    public ControlSubGraphFactory(WorkflowStepActions actions, GraphProperties properties, Clock clock) {
        this.actions = actions; nodes = new DeviceStepNodes(actions, properties, clock); executor = new StepAttemptExecutor(properties, clock);
        simulated = properties.mode().equals("stub");
    }
    public StateGraph<AssistantState> build() throws GraphStateException {
        var graph = new StateGraph<>(AssistantState.schema(), AssistantState::new);
        graph.addNode("ResolveCommand", guarded(nodes::resolve))
                .addNode("PrepareInput", guarded(new PrepareInput()::apply))
                .addNode("AwaitInput", guarded(new AwaitInput()::apply))
                .addNode("ValidatePermissionAndRisk", guarded(nodes::validate))
                .addNode("PrepareApproval", guarded(nodes::prepareApproval))
                .addNode("AwaitApproval", guarded(nodes::awaitApproval))
                .addNode("Revalidate", guarded(nodes::revalidate))
                .addNode("SaveCommandIntent", guarded(nodes::prepareCommand))
                .addNode("ExecuteCommand", guarded(s -> executor.execute(s, d -> {
                    if (!nodes.approved(s)) throw new SecurityException("Approval is no longer valid");
                    actions.revalidate(s); return actions.control(s, d);
                }, actions.supportsSafeCommandRetry())))
                .addNode("RetryWait", guarded(executor::waitForRetry))
                .addNode("SaveCommandResult", guarded(s -> { actions.commitResult(s, s.plan().results().get(s.plan().step().stepId())); return Map.of(); }));
        graph.addEdge(StateGraph.START, "ResolveCommand");
        graph.addConditionalEdges("ResolveCommand", edge_async(s -> failed(s) ? "end"
                        : s.workflow().status() == WorkflowStatus.WAITING_INPUT ? "input" : "validate"),
                Map.of("end", StateGraph.END, "input", "PrepareInput", "validate", "ValidatePermissionAndRisk"));
        graph.addEdge("PrepareInput", "AwaitInput");
        graph.addConditionalEdges("AwaitInput", edge_async(s -> s.workflow().status() == WorkflowStatus.WAITING_INPUT ? "wait" : "resolve"),
                Map.of("wait", "PrepareInput", "resolve", "ResolveCommand"));
        graph.addConditionalEdges("ValidatePermissionAndRisk", edge_async(s -> failed(s) ? "end" : nodes.approved(s) ? "run" : "wait"),
                Map.of("end", StateGraph.END, "run", "Revalidate", "wait", "PrepareApproval"));
        graph.addConditionalEdges("PrepareApproval", edge_async(s -> failed(s) ? "end" : nodes.approved(s) ? "run" : "wait"),
                Map.of("end", StateGraph.END, "run", "Revalidate", "wait", "AwaitApproval"));
        graph.addConditionalEdges("AwaitApproval", edge_async(s -> failed(s) ? "end" : nodes.approved(s) ? "run" : "wait"),
                Map.of("end", StateGraph.END, "run", "Revalidate", "wait", "PrepareApproval"));
        graph.addConditionalEdges("Revalidate", edge_async(s -> failed(s) ? "end" : nodes.approved(s) ? "run" : "wait"),
                Map.of("end", StateGraph.END, "run", "SaveCommandIntent", "wait", "PrepareApproval"));
        next(graph, "SaveCommandIntent", "ExecuteCommand");
        graph.addConditionalEdges("ExecuteCommand", edge_async(s -> failed(s) ? "end" : s.workflow().status() == WorkflowStatus.RETRYING ? "retry" : "save"),
                Map.of("end", StateGraph.END, "retry", "RetryWait", "save", "SaveCommandResult"));
        next(graph, "RetryWait", "Revalidate"); graph.addEdge("SaveCommandResult", StateGraph.END);
        return graph;
    }
    private org.bsc.langgraph4j.action.AsyncNodeAction<AssistantState> guarded(org.bsc.langgraph4j.action.NodeAction<AssistantState> node) {
        return NodeGuard.guarded(node, simulated, actions);
    }
}
