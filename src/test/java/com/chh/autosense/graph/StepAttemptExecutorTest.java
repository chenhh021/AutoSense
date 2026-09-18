package com.chh.autosense.graph;

import com.chh.autosense.config.GraphProperties;
import com.chh.autosense.domain.enums.*;
import com.chh.autosense.graph.node.*;
import com.chh.autosense.graph.state.*;
import org.junit.jupiter.api.Test;
import java.net.SocketTimeoutException;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class StepAttemptExecutorTest {
    private final GraphProperties properties = new GraphProperties("stub", 8, 30, null, 2, 0, 300, 30, 300, 256);
    private AssistantState initial() {
        var state = new AssistantState(AssistantState.initial(new AssistantState.RequestContext("request", 1, 1, "question")));
        return GraphUpdates.apply(state, Map.of(AssistantState.PLAN, new AssistantState.PlanContext(new ExecutionPlan(
                List.of(StubWorkflowActions.step("s1", PlanStepType.KNOWLEDGE_CONSULT)), "hash"), 0, Map.of(), Map.of())));
    }

    @Test void retryReusesSuccessfulSubcallAndRemainingDeadlineShrinks() {
        var clock = new MutableClock();
        var executor = new StepAttemptExecutor(properties, clock);
        var first = new AtomicInteger(); var second = new AtomicInteger();
        var budgets = new ArrayList<Duration>();
        StepAttemptExecutor.Call<Map<String, Object>> action = remaining -> {
            AttemptCalls.current().call("retrieval", budget -> {
                budgets.add(budget); first.incrementAndGet(); clock.advance(10); return "evidence";
            });
            Object answer = AttemptCalls.current().call("answer", budget -> {
                budgets.add(budget);
                if (second.incrementAndGet() == 1) throw new SocketTimeoutException();
                return "answer";
            });
            return Map.of("answer", answer);
        };
        var state = GraphUpdates.apply(initial(), executor.execute(initial(), action, true));
        assertThat(state.workflow().status()).isEqualTo(WorkflowStatus.RETRYING);
        var done = GraphUpdates.apply(state, executor.execute(state, action, true));
        assertThat(done.plan().results().get("s1").successful()).isTrue();
        assertThat(first).hasValue(1); assertThat(second).hasValue(2);
        assertThat(budgets.get(1)).isLessThan(budgets.getFirst());
    }

    @Test void exhaustedActiveSliceDoesNotStartAnotherCall() {
        var clock = new MutableClock(); var executor = new StepAttemptExecutor(properties, clock);
        var attempts = new AtomicInteger();
        var state = GraphUpdates.apply(initial(), executor.execute(initial(), remaining -> {
            attempts.incrementAndGet(); throw new SocketTimeoutException();
        }, true));
        clock.advance(301);
        var failed = GraphUpdates.apply(state, executor.execute(state, remaining -> { attempts.incrementAndGet(); return Map.of(); }, true));
        assertThat(attempts).hasValue(1);
        assertThat(failed.workflow().failureCode()).isEqualTo("WORKFLOW_TIMEOUT");
    }

    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-15T00:00:00Z");
        synchronized void advance(int seconds) { now = now.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public synchronized Instant instant() { return now; }
    }
}
