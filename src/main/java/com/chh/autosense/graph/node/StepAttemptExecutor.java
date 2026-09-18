package com.chh.autosense.graph.node;

import com.chh.autosense.config.GraphProperties;
import com.chh.autosense.domain.enums.*;
import com.chh.autosense.graph.state.*;
import java.net.SocketTimeoutException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.chh.autosense.graph.state.AssistantState.*;

/** One attempt per graph visit, so retries remain visible and checkpointable. */
@lombok.extern.slf4j.Slf4j
public final class StepAttemptExecutor {
    @FunctionalInterface public interface Call<T> { T run(Duration remaining) throws Exception; }
    private final GraphProperties properties;
    private final Clock clock;
    public StepAttemptExecutor(GraphProperties properties, Clock clock) { this.properties = properties; this.clock = clock; }

    public Map<String, Object> execute(AssistantState state, Call<Map<String, Object>> call, boolean safeCommandRetry) {
        return attempt(state, properties.stepTimeout(state.plan().step().type().name()), call, data ->
                new LinkedHashMap<>(GraphUpdates.result(state, new ExecutionPlan.Result(ExecutionPlan.StepStatus.COMPLETED,
                        data, "", ExecutionPlan.Certainty.SUCCEEDED, state.retry().retriesUsed()))), safeCommandRetry,
                state.plan().step().type() == PlanStepType.DEVICE_CONTROL);
    }

    public Map<String, Object> plan(AssistantState state, Call<WorkflowStepActions.PlanProposal> call) {
        return attempt(state, Duration.ofSeconds(properties.plannerTimeoutSeconds()), call, proposal -> {
            var delta = new LinkedHashMap<String, Object>();
            delta.put(PLAN, new PlanContext(new ExecutionPlan(proposal.steps(), ""), 0, Map.of(), Map.of(),
                    proposal.outcome(), Objects.toString(proposal.clarifyQuestion(), "")));
            delta.putAll(GraphUpdates.event(GraphUpdates.apply(state, delta), WorkflowStatus.VALIDATING,
                    "STATUS", "VALIDATING", "正在校验执行计划。", Map.of()));
            return delta;
        }, true, false);
    }

    private <T> Map<String, Object> attempt(AssistantState state, Duration timeoutBudget, Call<T> call,
            java.util.function.Function<T, Map<String, Object>> success, boolean safeCommandRetry, boolean command) {
        var retry = state.retry();
        var cache = new LinkedHashMap<>(retry.completedCalls());
        long now = clock.millis();
        long deadline = ((Number) cache.computeIfAbsent("sliceDeadline", ignored ->
                now + properties.executionSliceTimeoutSeconds() * 1000L)).longValue();
        Duration remaining = Duration.ofMillis(Math.min(timeoutBudget.toMillis(), deadline - now));
        if (remaining.isZero() || remaining.isNegative()) return GraphUpdates.failure(state, "WORKFLOW_TIMEOUT", ExecutionPlan.Certainty.NOT_SENT);
        String stepId = state.plan().executionPlan().steps().isEmpty() ? "planner" : state.plan().step().stepId();
        String attemptId = UUID.nameUUIDFromBytes((state.request().requestId() + ":" + stepId + ":" + retry.retriesUsed())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        var calls = new AttemptCalls(clock, clock.instant().plus(remaining), cache);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            var context = com.chh.autosense.utils.LogContextUtils.with(com.chh.autosense.utils.LogContextUtils.snapshot(), "attemptId", attemptId);
            var tokenSink = AiTokenStreamAdapter.capture();
            Future<T> future = executor.submit(() -> {
                try (var ignored = com.chh.autosense.utils.LogContextUtils.install(context); var tokens = AiTokenStreamAdapter.install(tokenSink)) {
                    return calls.run(() -> call.run(remaining));
                }
            });
            try {
                T data = future.get(remaining.toMillis(), TimeUnit.MILLISECONDS);
                cache.putAll(calls.finish());
                var delta = success.apply(data);
                delta.put(RETRY, new RetryContext(retry.retriesUsed(), attemptId, "", null, cache));
                return delta;
            } catch (Exception e) {
                future.cancel(true);
                cache.putAll(calls.finish());
                Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
                log.warn("Workflow attempt failed: stepId={}, attemptId={}, errorType={}", stepId, attemptId, cause.getClass().getSimpleName());
                if (cause instanceof StepFailure failure) return GraphUpdates.failure(state, failure.code(), failure.certainty());
                boolean timeout = isTimeout(cause);
                if (cause instanceof InterruptedException) Thread.currentThread().interrupt();
                if (timeout && command && !safeCommandRetry)
                    return GraphUpdates.failure(state, "DEVICE_RESULT_UNKNOWN", ExecutionPlan.Certainty.UNKNOWN);
                if (timeout && retry.retriesUsed() < properties.maxRetries()
                        && clock.millis() + properties.retryDelayMillis() < deadline) {
                    var delta = GraphUpdates.event(state, WorkflowStatus.RETRYING, "STATUS", "RETRYING",
                            "请求超时，正在重试。", Map.of("retriesUsed", retry.retriesUsed() + 1));
                    delta.put(RETRY, new RetryContext(retry.retriesUsed() + 1, attemptId, "REQUEST_TIMEOUT",
                            clock.instant().plusMillis(properties.retryDelayMillis()), cache));
                    return delta;
                }
                return GraphUpdates.failure(state, timeout ? "REQUEST_TIMEOUT" : cause instanceof SecurityException
                        ? "EXECUTION_REFUSED" : "STEP_EXECUTION_FAILED", timeout ? ExecutionPlan.Certainty.UNKNOWN : ExecutionPlan.Certainty.FAILED);
            }
        } finally {
            // close() waits for an uncooperative provider indefinitely; fencing rejects late results.
            executor.shutdownNow();
        }
    }

    private static boolean isTimeout(Throwable error) {
        for (var cause = error; cause != null; cause = cause.getCause())
            if (cause instanceof TimeoutException || cause instanceof SocketTimeoutException || cause instanceof java.net.http.HttpTimeoutException) return true;
        return false;
    }

    public Map<String, Object> waitForRetry(AssistantState state) throws InterruptedException {
        Instant next = state.retry().nextRetryAt();
        if (next != null) {
            long wait = Duration.between(clock.instant(), next).toMillis();
            if (wait > 0) Thread.sleep(wait);
        }
        return GraphUpdates.event(state, WorkflowStatus.RUNNING, "STATUS", "RUNNING", "正在执行步骤。", Map.of());
    }
}
