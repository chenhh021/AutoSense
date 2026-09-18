package com.chh.autosense.graph.node;

import com.chh.autosense.graph.state.StateData;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Shares an attempt deadline and preserves completed subcalls across a timeout retry. */
public final class AttemptCalls {
    private static final ThreadLocal<AttemptCalls> CURRENT = new ThreadLocal<>();
    private final Clock clock;
    private final Instant deadline;
    private final Map<String, Object> completed;
    private volatile boolean closed;
    AttemptCalls(Clock clock, Instant deadline, Map<String, Object> completed) {
        this.clock = clock; this.deadline = deadline; this.completed = new LinkedHashMap<>(completed);
    }
    public static AttemptCalls current() {
        var scope = CURRENT.get();
        if (scope == null) throw new IllegalStateException("No graph attempt scope");
        return scope;
    }
    <T> T run(Callable<T> action) throws Exception {
        CURRENT.set(this);
        try { return action.call(); } finally { CURRENT.remove(); }
    }
    public Duration remaining() throws TimeoutException {
        if (closed) throw new TimeoutException("Attempt has ended");
        Duration remaining = Duration.between(clock.instant(), deadline);
        if (remaining.isNegative() || remaining.isZero()) throw new TimeoutException("Attempt deadline exceeded");
        return remaining;
    }
    public static Duration limit(Duration configured) {
        var current = CURRENT.get();
        if (current == null) return configured;
        try {
            var remaining = current.remaining();
            return configured.compareTo(remaining) < 0 ? configured : remaining;
        } catch (TimeoutException e) { throw new CompletionException(e); }
    }
    public Object call(String callId, StepAttemptExecutor.Call<Object> action) throws Exception {
        if (callId == null || !callId.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("Invalid call identity");
        String key = "call:" + callId;
        synchronized (this) {
            if (closed) throw new TimeoutException("Attempt has ended");
            if (completed.containsKey(key)) return completed.get(key);
        }
        Object result = StateData.freezeValue(action.run(remaining()));
        synchronized (this) {
            if (closed) throw new TimeoutException("Attempt has ended");
            remaining(); completed.put(key, result);
        }
        return result;
    }
    synchronized Map<String, Object> finish() {
        closed = true; return StateData.freeze(completed);
    }
}
