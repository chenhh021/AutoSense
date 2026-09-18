package com.chh.autosense.graph;

import com.chh.autosense.config.GraphProperties;
import com.chh.autosense.domain.enums.WorkflowStatus;
import com.chh.autosense.graph.node.*;
import com.chh.autosense.graph.state.*;
import com.chh.autosense.graph.subgraph.*;
import org.bsc.langgraph4j.*;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import java.time.Clock;
import java.util.*;
import static com.chh.autosense.graph.state.AssistantState.*;
import static com.chh.autosense.graph.node.NodeGuard.*;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;

/** One compilation owns all inline subgraphs, state, interrupts and checkpoints. */
public final class MainGraphFactory {
    private final GraphProperties properties;
    private final WorkflowStepActions actions;
    private final Clock clock;
    public MainGraphFactory(GraphProperties properties, WorkflowStepActions actions, Clock clock) {
        this.properties = properties; this.actions = actions; this.clock = clock;
    }

    public CompiledGraph<AssistantState> compile(BaseCheckpointSaver saver) throws GraphStateException {
        var graph = new StateGraph<>(AssistantState.schema(), new com.chh.autosense.graph.checkpoint.AssistantStateSerializer());
        var executor = new StepAttemptExecutor(properties, clock);
        graph.addNode("IntentPlanner", guarded(new IntentPlanner(actions, executor)::apply))
                .addNode("PlannerRetryWait", guarded(executor::waitForRetry))
                .addNode("PlanValidator", observed(this::validate, properties.mode().equals("stub"), actions))
                .addNode("PlanRouter", guarded(this::route))
                .addNode("KnowledgeConsult", guarded(new KnowledgeConsult(actions, executor)::apply))
                .addNode("KnowledgeRetryWait", guarded(executor::waitForRetry))
                .addNode("QuerySubGraph", new QuerySubGraphFactory(actions, properties, clock).build())
                .addNode("DiagnosisSubGraph", new DiagnosisSubGraphFactory(actions, properties, clock).build())
                .addNode("ControlSubGraph", new ControlSubGraphFactory(actions, properties, clock).build())
                .addNode("CompleteStep", guarded(new CompleteStep(actions)::apply))
                .addNode("ResponseAggregator", guarded(new ResponseAggregator()::apply))
                .addNode("Reject", observed(new Reject(actions)::apply, properties.mode().equals("stub"), actions))
                .addNode("PrepareInput", guarded(new PrepareInput()::apply))
                .addNode("AwaitInput", guarded(new AwaitInput()::apply));
        graph.addEdge(StateGraph.START, "IntentPlanner");
        graph.addConditionalEdges("IntentPlanner", edge_async(s -> failed(s) ? "reject"
                        : s.workflow().status() == WorkflowStatus.RETRYING ? "retry" : "validate"),
                Map.of("reject", "Reject", "validate", "PlanValidator", "retry", "PlannerRetryWait"));
        graph.addEdge("PlannerRetryWait", "IntentPlanner");
        graph.addConditionalEdges("PlanValidator", edge_async(s -> failed(s) ? "reject" : s.plan().candidateOutcome()),
                Map.of("reject", "Reject", "PLAN", "PlanRouter", "CLARIFY", "PrepareInput", "OUT_OF_SCOPE", "ResponseAggregator"));
        graph.addConditionalEdges("PlanRouter", edge_async(s -> {
            if (failed(s)) return "reject";
            var result = s.plan().results().get(s.plan().step().stepId());
            return result != null && (result.successful() || result.status() == ExecutionPlan.StepStatus.SKIPPED)
                    ? "complete" : s.plan().step().type().name();
        }), Map.of("reject", "Reject", "complete", "CompleteStep", "KNOWLEDGE_CONSULT", "KnowledgeConsult",
                "DEVICE_QUERY", "QuerySubGraph", "FAULT_DIAGNOSIS", "DiagnosisSubGraph", "DEVICE_CONTROL", "ControlSubGraph"));
        graph.addConditionalEdges("KnowledgeConsult", edge_async(s -> failed(s) ? "reject"
                        : s.workflow().status() == WorkflowStatus.RETRYING ? "retry" : "complete"),
                Map.of("reject", "Reject", "retry", "KnowledgeRetryWait", "complete", "CompleteStep"));
        graph.addEdge("KnowledgeRetryWait", "KnowledgeConsult");
        for (String subgraph : List.of("QuerySubGraph", "DiagnosisSubGraph", "ControlSubGraph"))
            graph.addConditionalEdges(subgraph, edge_async(s -> failed(s) ? "reject" : "complete"),
                    Map.of("reject", "Reject", "complete", "CompleteStep"));
        graph.addConditionalEdges("CompleteStep", edge_async(s -> failed(s) ? "reject"
                        : s.plan().currentStep() < s.plan().executionPlan().steps().size() ? "next" : "done"),
                Map.of("reject", "Reject", "next", "PlanRouter", "done", "ResponseAggregator"));
        graph.addEdge("PrepareInput", "AwaitInput");
        graph.addConditionalEdges("AwaitInput", edge_async(s -> s.workflow().status() == WorkflowStatus.WAITING_INPUT
                        ? "wait" : s.workflow().returnNode()),
                Map.of("wait", "PrepareInput", "IntentPlanner", "IntentPlanner", "PlanRouter", "PlanRouter"));
        graph.addEdge("ResponseAggregator", StateGraph.END); graph.addEdge("Reject", StateGraph.END);
        return graph.compile(CompileConfig.builder().checkpointSaver(saver).releaseThread(false)
                .recursionLimit(properties.maxGraphIterations()).interruptBefore("AwaitInput",
                        SubGraphNode.formatId("QuerySubGraph", "AwaitApproval"),
                        SubGraphNode.formatId("QuerySubGraph", "AwaitInput"),
                        SubGraphNode.formatId("ControlSubGraph", "AwaitInput"),
                        SubGraphNode.formatId("ControlSubGraph", "AwaitApproval")).build());
    }

    private Map<String, Object> validate(AssistantState state) {
        var p = state.plan();
        try {
            var published = new PlanValidator(properties).validate(new WorkflowStepActions.PlanProposal(
                    p.candidateOutcome(), p.executionPlan().steps(), p.clarifyQuestion()));
            Object deadline = state.retry().completedCalls().get("sliceDeadline");
            return Map.of(PLAN, new PlanContext(published, 0, Map.of(), Map.of(), p.candidateOutcome(), p.clarifyQuestion()),
                    RETRY, new RetryContext(0, "", "", null, deadline == null ? Map.of() : Map.of("sliceDeadline", deadline)));
        } catch (PlanValidator.ClarificationRequired e) {
            return Map.of(PLAN, new PlanContext(ExecutionPlan.empty(), 0, Map.of(), Map.of(), "CLARIFY", "请补充设备类型及咨询需求。"));
        } catch (IllegalArgumentException e) {
            return GraphUpdates.event(state, WorkflowStatus.REJECTED, "STATUS", "INVALID_PLAN", "无法安全执行此计划。", Map.of());
        }
    }

    private org.bsc.langgraph4j.action.AsyncNodeAction<AssistantState> guarded(org.bsc.langgraph4j.action.NodeAction<AssistantState> node) {
        return NodeGuard.guarded(node, properties.mode().equals("stub"), actions);
    }

    private Map<String, Object> route(AssistantState state) {
        var router = new PlanRouter();
        var old = state.plan();
        var existing = old.results().get(old.step().stepId());
        if (existing != null && (existing.successful() || existing.status() == ExecutionPlan.StepStatus.SKIPPED)) return Map.of();
        if (!router.shouldRun(state)) return GraphUpdates.result(state, new ExecutionPlan.Result(ExecutionPlan.StepStatus.SKIPPED,
                Map.of(), "", ExecutionPlan.Certainty.NOT_SENT, 0));
        var delta = GraphUpdates.event(state, WorkflowStatus.RUNNING, "STATUS", "RUNNING", "正在执行步骤。", Map.of());
        delta.put(PLAN, new PlanContext(old.executionPlan(), old.currentStep(), router.inputs(state), old.results(), old.candidateOutcome(), ""));
        return delta;
    }
}
