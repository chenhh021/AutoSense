package com.chh.autosense.graph;

import com.chh.autosense.config.GraphProperties;
import com.chh.autosense.graph.MainGraphFactory;
import com.chh.autosense.graph.node.StubWorkflowActions;
import com.chh.autosense.graph.state.AssistantState;
import org.bsc.langgraph4j.*;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class GraphStreamContractTest {
    @Test void projectsOnlyPublicOutputAndDeduplicatesRepeatedSnapshots() throws Exception {
        var graph = new MainGraphFactory(new GraphProperties("stub", 8, 30, null, 2, 0, 300, 30, 300, 256),
                new StubWorkflowActions(), Clock.systemUTC()).compile(new MemorySaver());
        var events = new ArrayList<com.chh.autosense.domain.message.WorkflowEvent>();
        new WorkflowEventStream().consume(graph.stream(GraphInput.args(AssistantState.initial(
                new AssistantState.RequestContext("stream-public", 1, 1, "什么是色温"))),
                RunnableConfig.builder().threadId("stream-public").build()), events::add);
        assertThat(events).extracting(e -> e.data().eventId()).doesNotHaveDuplicates();
        assertThat(events).extracting(e -> e.type()).containsSubsequence("STEP_RESULT", "CONCLUSION");
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(events);
        assertThat(json).doesNotContain("requestContext", "messages", "approvalRef", "permission");
    }

    @Test void waitNotificationIsEmittedAfterIteratorFinishesAndNotAfterFailure() {
        var state = new AssistantState(AssistantState.initial(new AssistantState.RequestContext("wait", 1, 1, "query")));
        var waiting = com.chh.autosense.graph.node.GraphUpdates.apply(state,
                com.chh.autosense.graph.node.GraphUpdates.event(state, com.chh.autosense.domain.enums.WorkflowStatus.WAITING_APPROVAL,
                        "CONFIRM", "WAITING_APPROVAL", "Confirm", Map.of()));
        var finished = new java.util.concurrent.atomic.AtomicBoolean();
        Iterable<NodeOutput<AssistantState>> stream = () -> new Iterator<>() {
            boolean emitted;
            public boolean hasNext() { if (emitted) finished.set(true); return !emitted; }
            public NodeOutput<AssistantState> next() { emitted = true; return NodeOutput.of("PrepareApproval", waiting); }
        };
        var received = new ArrayList<String>();
        new WorkflowEventStream().consume(stream, event -> {
            assertThat(finished).isTrue(); received.add(event.type());
        });
        assertThat(received).containsExactly("CONFIRM");
        Iterable<NodeOutput<AssistantState>> broken = () -> new Iterator<>() {
            boolean emitted;
            public boolean hasNext() { if (emitted) throw new IllegalStateException("checkpoint failed"); return true; }
            public NodeOutput<AssistantState> next() { emitted = true; return NodeOutput.of("PrepareApproval", waiting); }
        };
        received.clear();
        assertThatThrownBy(() -> new WorkflowEventStream().consume(broken, event -> received.add(event.type())))
                .isInstanceOf(IllegalStateException.class);
        assertThat(received).isEmpty();
    }

    @Test void knowledgePublishesStepResultBeforeOneFinalConclusion() throws Exception {
        var factory = new MainGraphFactory(new GraphProperties("stub", 8, 30, null, 2, 0, 300, 30, 300, 256),
                new StubWorkflowActions(), Clock.systemUTC());
        var graph = factory.compile(new MemorySaver());
        var events = new LinkedHashMap<String, AssistantState.OutputContext>();
        for (var output : graph.stream(GraphInput.args(AssistantState.initial(
                new AssistantState.RequestContext("request-knowledge", 1, 1, "什么是色温"))),
                RunnableConfig.builder().threadId("request-knowledge").build())) {
            var event = output.state().output();
            if (!event.type().isBlank()) events.put((String) event.data().get("eventId"), event);
        }
        assertThat(events.values()).extracting(AssistantState.OutputContext::type)
                .containsSubsequence("STEP_RESULT", "CONCLUSION");
        assertThat(events.values().stream().filter(e -> e.type().equals("CONCLUSION"))).hasSize(1);
        assertThat(events.values()).allSatisfy(e -> assertThat(e.data())
                .doesNotContainKeys("messages", "prompt", "approval", "permission", "rawState"));
        assertThat(events.values()).allSatisfy(e -> {
            assertThat(e.code()).startsWith("STUB_");
            assertThat(((Map<?, ?>) e.data().get("payload")).get("simulated")).isEqualTo(true);
        });
    }
}
