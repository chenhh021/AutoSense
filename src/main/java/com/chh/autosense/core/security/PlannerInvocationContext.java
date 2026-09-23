package com.chh.autosense.core.security;

import com.chh.autosense.ai.model.DeviceListResult;
import com.chh.autosense.exception.ApiException;
import com.chh.autosense.exception.ErrorCode;
import com.chh.autosense.graph.node.AttemptCalls;
import com.chh.autosense.graph.node.PlanValidator;
import com.chh.autosense.graph.state.AssistantState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/** Per synchronous planner invocation; never stored in the singleton tool or persisted state. */
public final class PlannerInvocationContext implements AutoCloseable {
    private static final ThreadLocal<PlannerInvocationContext> CURRENT = new ThreadLocal<>();
    private static final String CALL_ID = "planner-device-list";
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final long userId;
    private final Instant deadline;
    private final Thread owner = Thread.currentThread();
    private AssistantState.DeviceContext snapshot;
    private RuntimeException failure;
    private boolean closed;

    private PlannerInvocationContext(AssistantState state, Duration remaining) {
        userId = state.request().userId();
        deadline = Instant.now().plus(remaining);
        snapshot = state.<AssistantState.DeviceContext>value(AssistantState.DEVICE).orElseThrow();
        Object saved = state.retry().completedCalls().get("call:" + CALL_ID);
        if (!snapshot.initialized() && saved instanceof String result) publish(result);
    }

    public static PlannerInvocationContext open(AssistantState state, Duration remaining) {
        if (CURRENT.get() != null) throw new IllegalStateException("Planner invocation already active");
        var scope = new PlannerInvocationContext(state, remaining);
        CURRENT.set(scope);
        return scope;
    }

    public static PlannerInvocationContext current() {
        var scope = CURRENT.get();
        if (scope == null) throw new SecurityException("No trusted planner invocation");
        return scope;
    }

    /** Also used before SDK follow-up model calls, since the SDK converts tool exceptions to text. */
    public static void checkActive() {
        var scope = CURRENT.get();
        if (scope != null) scope.checkHealthy();
    }

    public void checkHealthy() {
        if (failure != null) throw failure;
        if (closed || Thread.currentThread() != owner) throw new SecurityException("Planner invocation is not active");
        if (!Instant.now().isBefore(deadline)) throw new CompletionException(new TimeoutException("Planner deadline exceeded"));
        AttemptCalls.limit(Duration.between(Instant.now(), deadline));
    }

    public AssistantState.DeviceContext deviceContext() { checkHealthy(); return snapshot; }

    public String deviceList(String requestedUser, Duration timeout, int maxBytes, Function<Instant, DeviceListResult> loader) {
        try {
            checkHealthy();
            long requested;
            try { requested = Long.parseLong(requestedUser == null ? "" : requestedUser.trim()); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid user identity"); }
            if (requested < 1) throw new IllegalArgumentException("Invalid user identity");
            if (requested != userId) throw new SecurityException("Device list user does not match current user");
            if (snapshot.initialized()) return encodeSnapshot(maxBytes);
            AttemptCalls calls;
            try { calls = AttemptCalls.current(); } catch (IllegalStateException noGraph) { calls = null; }
            String result = calls == null ? load(timeout, maxBytes, loader)
                    : (String) calls.call(CALL_ID, ignored -> load(timeout, maxBytes, loader));
            checkHealthy();
            publish(result);
            return result;
        } catch (Exception e) {
            failure = e instanceof RuntimeException runtime ? runtime : new CompletionException(e);
            // The SDK must never send underlying service exception text to the model.
            throw new IllegalStateException("Device list tool failed");
        }
    }

    private String load(Duration timeout, int maxBytes, Function<Instant, DeviceListResult> loader) throws Exception {
        var budget = AttemptCalls.limit(timeout);
        Instant end = Instant.now().plus(budget);
        var result = loader.apply(end.isBefore(deadline) ? end : deadline);
        checkHealthy();
        if (result == null || !"OK".equals(result.errorCode()) || result.observedAt() == null)
            throw new IllegalStateException("Invalid device list result");
        return bounded(JSON.writeValueAsString(result), maxBytes);
    }

    private String encodeSnapshot(int maxBytes) throws Exception {
        return bounded(JSON.writeValueAsString(Map.of("devices", snapshot.planningDevices(),
                "statusMetadata", snapshot.statusMetadata(), "basicMetadata", snapshot.basicMetadata(),
                "observedAt", snapshot.initializedAt(), "errorCode", "OK")), maxBytes);
    }

    private static String bounded(String text, int maxBytes) {
        if (text.getBytes(StandardCharsets.UTF_8).length > maxBytes)
            throw new ApiException(ErrorCode.DEVICE_CONTEXT_TOO_LARGE, "Device context exceeds the configured size limit");
        return text;
    }

    private void publish(String value) {
        try {
            var result = JSON.readValue(value, DeviceListResult.class);
            if (!"OK".equals(result.errorCode()) || result.observedAt() == null)
                throw new IllegalArgumentException("Invalid saved device list");
            Map<String, Object> fields = JSON.readValue(value, new com.fasterxml.jackson.core.type.TypeReference<>() { });
            snapshot = new AssistantState.DeviceContext(snapshot.target(), snapshot.snapshots(), result.devices(),
                    object(fields.get("statusMetadata")), object(fields.get("basicMetadata")), true,
                    result.observedAt().toString(), PlanValidator.digest(fields));
        } catch (java.io.IOException e) { throw new IllegalStateException("Invalid saved device list"); }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) { return (Map<String, Object>) value; }

    @Override public void close() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Planner scope closed on another thread");
        closed = true;
        CURRENT.remove();
    }
}
