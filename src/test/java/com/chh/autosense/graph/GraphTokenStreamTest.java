package com.chh.autosense.graph;

import com.chh.autosense.graph.node.AiTokenStreamAdapter;
import com.chh.autosense.graph.state.AssistantState;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class GraphTokenStreamTest {
    @Test void actualGraphEmbedsTokensResetsFailedAttemptAndPersistsOnlyFinalText() throws Exception {
        var actions = mock(com.chh.autosense.graph.node.WorkflowStepActions.class);
        var step = new com.chh.autosense.graph.state.ExecutionPlan.Step("s1", com.chh.autosense.domain.enums.PlanStepType.KNOWLEDGE_CONSULT,
                "answer", null, Map.of(), List.of(), Map.of(), null, false, null);
        when(actions.plan(any(), any())).thenReturn(new com.chh.autosense.graph.node.WorkflowStepActions.PlanProposal("PLAN", List.of(step), null));
        when(actions.streamsAnswers()).thenReturn(true);
        when(actions.afterNode(any(), any())).thenAnswer(i -> {
            Map<String, Object> delta = i.getArgument(1);
            if (delta.get(AssistantState.OUTPUT) instanceof AssistantState.OutputContext output) assertThat(output.type()).isNotIn("TEXT", "TEXT_RESET");
            return delta;
        });
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        when(actions.answer(any(), any())).thenAnswer(i -> {
            if (calls.getAndIncrement() == 0) { AiTokenStreamAdapter.capture().accept("discard"); throw new java.net.SocketTimeoutException(); }
            AiTokenStreamAdapter.capture().accept("final"); return Map.of("answer", "final");
        });
        var props = new com.chh.autosense.config.GraphProperties("real", 8, 30, null, 2, 0, 300, 30, 300, 256);
        var graph = new MainGraphFactory(props, actions, java.time.Clock.systemUTC()).compile(new org.bsc.langgraph4j.checkpoint.MemorySaver());
        var config = org.bsc.langgraph4j.RunnableConfig.builder().threadId("stream").build();
        var events = new ArrayList<com.chh.autosense.domain.message.WorkflowEvent>();
        new WorkflowEventStream().consume(graph.stream(org.bsc.langgraph4j.GraphInput.args(AssistantState.initial(new AssistantState.RequestContext("stream", 1, 1, "question"))), config), events::add);
        assertThat(events).extracting(com.chh.autosense.domain.message.WorkflowEvent::type).contains("TEXT", "TEXT_RESET", "STEP_RESULT", "CONCLUSION");
        assertThat(graph.getState(config).state().plan().results().get("s1").data()).containsEntry("answer", "final");
        assertThat(calls).hasValue(2);
    }

    @Test void fullQueueFailsTheAttemptWithoutSilentlyDroppingText() throws Exception {
        var base = new AssistantState(AssistantState.initial(new AssistantState.RequestContext("overflow", 1, 1, "question")));
        var step = new com.chh.autosense.graph.state.ExecutionPlan.Step("s1", com.chh.autosense.domain.enums.PlanStepType.KNOWLEDGE_CONSULT,
                "answer", null, Map.of(), List.of(), Map.of(), null, false, null);
        var state = com.chh.autosense.graph.node.GraphUpdates.apply(base, Map.of(AssistantState.PLAN,
                new AssistantState.PlanContext(new com.chh.autosense.graph.state.ExecutionPlan(List.of(step), "hash"), 0, Map.of(), Map.of(), "PLAN", "")));
        var completed = new java.util.concurrent.CountDownLatch(1);
        var actions = mock(com.chh.autosense.graph.node.WorkflowStepActions.class);
        when(actions.afterNode(any(), any())).thenAnswer(i -> { completed.countDown(); return i.getArgument(1); });
        var executor = new com.chh.autosense.graph.node.StepAttemptExecutor(new com.chh.autosense.config.GraphProperties("real", 8, 30, null, 2, 0, 300, 30, 300, 256), java.time.Clock.systemUTC());
        var generator = AiTokenStreamAdapter.embed(state, "KnowledgeConsult", () -> executor.execute(state, duration -> {
            for (int n = 0; n < 257; n++) AiTokenStreamAdapter.capture().accept("part");
            return Map.of("answer", "incorrect");
        }, true), actions);
        assertThat(completed.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        int resets = 0;
        org.bsc.async.AsyncGenerator.Data<org.bsc.langgraph4j.NodeOutput<AssistantState>> finalItem;
        while (true) {
            finalItem = generator.next();
            if (finalItem.isDone()) break;
            if (finalItem.future().join().state().output().type().equals("TEXT_RESET")) resets++;
        }
        assertThat(resets).isEqualTo(1);
        @SuppressWarnings("unchecked") var result = (Map<String, Object>) finalItem.resultValue();
        assertThat(com.chh.autosense.graph.node.GraphUpdates.apply(state, result).workflow().failureCode()).isEqualTo("OUTPUT_OVERFLOW");
    }
    @Test void synchronousAndAsynchronousCallbacksCompleteWithFullText() throws Exception {
        for (boolean async : List.of(false, true)) {
            var fixture = fixture();
            doAnswer(i -> {
                Runnable emit = () -> { fixture.partial.get().accept("hello"); fixture.complete.get().accept(response("hello")); };
                if (async) Thread.startVirtualThread(emit); else emit.run();
                return null;
            }).when(fixture.stream).start();
            assertThat(AiTokenStreamAdapter.collect(fixture.stream, "", Duration.ofSeconds(2))).isEqualTo("hello");
        }
    }
    @Test void timeoutTerminatesCallbacksAndLateTextCannotPolluteAnotherAttempt() throws Exception {
        var fixture = fixture();
        assertThatThrownBy(() -> AiTokenStreamAdapter.collect(fixture.stream, "", Duration.ofMillis(20)))
                .isInstanceOf(java.util.concurrent.TimeoutException.class);
        fixture.partial.get().accept("late"); fixture.complete.get().accept(response("late"));
        var next = fixture();
        doAnswer(i -> { next.partial.get().accept("new"); next.complete.get().accept(response("new")); return null; }).when(next.stream).start();
        assertThat(AiTokenStreamAdapter.collect(next.stream, "", Duration.ofSeconds(1))).isEqualTo("new");
    }
    private ChatResponse response(String text) { return ChatResponse.builder().aiMessage(AiMessage.from(text)).build(); }
    private record Fixture(TokenStream stream, AtomicReference<Consumer<String>> partial, AtomicReference<Consumer<ChatResponse>> complete) { }
    private Fixture fixture() {
        var stream = mock(TokenStream.class); var partial = new AtomicReference<Consumer<String>>(); var complete = new AtomicReference<Consumer<ChatResponse>>();
        when(stream.onPartialResponse(any())).thenAnswer(i -> { partial.set(i.getArgument(0)); return stream; });
        when(stream.onCompleteResponse(any())).thenAnswer(i -> { complete.set(i.getArgument(0)); return stream; });
        when(stream.onError(any())).thenReturn(stream);
        return new Fixture(stream, partial, complete);
    }
}
