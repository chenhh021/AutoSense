package com.chh.autosense.graph.node;

import com.chh.autosense.domain.enums.WorkflowStatus;
import com.chh.autosense.graph.state.AssistantState;
import com.chh.autosense.graph.state.ExecutionPlan;
import com.chh.autosense.utils.*;
import dev.langchain4j.service.TokenStream;
import org.bsc.async.AsyncGenerator;
import org.bsc.langgraph4j.NodeOutput;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Bounded callback-to-generator bridge. Only the completed delta enters durable graph state. */
public final class AiTokenStreamAdapter {
    @FunctionalInterface public interface Sink { void accept(String text); }
    private static final ThreadLocal<Sink> CURRENT = new ThreadLocal<>();
    private AiTokenStreamAdapter() { }
    public static Sink capture() { return CURRENT.get(); }
    public static LogContextUtils.Scope install(Sink sink) {
        Sink prior = CURRENT.get();
        if (sink == null) CURRENT.remove(); else CURRENT.set(sink);
        return () -> { if (prior == null) CURRENT.remove(); else CURRENT.set(prior); };
    }

    public static String collect(TokenStream stream, String prefix, Duration remaining) throws Exception {
        var result = new CompletableFuture<String>();
        var closed = new AtomicBoolean(); var text = new StringBuilder();
        var sink = capture(); var context = LogContextUtils.snapshot();
        var log = AiCallLog.start("directAnswer");
        try {
            log.phase(AiCallLog.Phase.STREAM_START);
            stream.onPartialResponse(token -> {
                try (var ignored = LogContextUtils.install(context)) {
                    synchronized (text) {
                        if (closed.get() || result.isDone()) return;
                        if (token == null || text.length() + token.length() > 1_000_000) throw new StepFailure("OUTPUT_OVERFLOW", ExecutionPlan.Certainty.NOT_SENT);
                        log.firstResponse();
                        if (text.isEmpty() && !prefix.isEmpty() && sink != null) sink.accept(prefix);
                        text.append(token); if (sink != null) sink.accept(token);
                    }
                } catch (RuntimeException e) { closed.set(true); result.completeExceptionally(e); }
            }).onCompleteResponse(response -> {
                try (var ignored = LogContextUtils.install(context)) {
                    synchronized (text) {
                        if (closed.get() || result.isDone()) return;
                        if (text.isEmpty()) result.completeExceptionally(new IllegalStateException("Empty streaming answer"));
                        else result.complete(prefix + text);
                    }
                }
            }).onError(error -> {
                try (var ignored = LogContextUtils.install(context)) {
                    if (!closed.get()) result.completeExceptionally(error == null ? new IllegalStateException("Model stream failed") : error);
                }
            }).start();
            String answer = result.get(Math.max(1, remaining.toMillis()), TimeUnit.MILLISECONDS);
            log.completed(); return answer;
        } catch (ExecutionException e) {
            log.failed(e.getCause(), AiCallLog.Phase.STREAM_RECEIVE);
            if (e.getCause() instanceof Exception failure) throw failure;
            throw new IllegalStateException("Model stream failed");
        } catch (Exception e) { log.failed(e, AiCallLog.Phase.STREAM_RECEIVE); throw e; }
        finally { closed.set(true); }
    }

    public static AsyncGenerator<NodeOutput<AssistantState>> embed(AssistantState state, String node,
            Supplier<Map<String, Object>> action, WorkflowStepActions persistence) {
        return new Generator(state, node, action, persistence);
    }
    private static final class Generator implements AsyncGenerator<NodeOutput<AssistantState>> {
        private final AssistantState state;
        private final String node;
        private final String attemptId = UUID.randomUUID().toString();
        private final ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<>(256);
        private final CompletableFuture<Map<String, Object>> completion = new CompletableFuture<>();
        private final AtomicBoolean closed = new AtomicBoolean();
        private long index;
        private boolean reset;
        Generator(AssistantState state, String node, Supplier<Map<String, Object>> action, WorkflowStepActions persistence) {
            this.state = state; this.node = node;
            var context = LogContextUtils.with(LogContextUtils.workflow(state), "attemptId", attemptId);
            Thread.startVirtualThread(() -> {
                try (var ignored = LogContextUtils.install(context); var tokens = install(text -> {
                    if (closed.get()) return;
                    if (!queue.offer(text)) throw new StepFailure("OUTPUT_OVERFLOW", ExecutionPlan.Certainty.NOT_SENT);
                })) {
                    completion.complete(persistence.afterNode(state, action.get()));
                } catch (Throwable e) { completion.completeExceptionally(e); }
                finally { closed.set(true); }
            });
        }
        @Override public Executor executor() { return ForkJoinPool.commonPool(); }
        @Override public Data<NodeOutput<AssistantState>> next() {
            try {
                while (true) {
                    String token = queue.poll(25, TimeUnit.MILLISECONDS);
                    if (token != null) return Data.of(output("TEXT", token));
                    if (!completion.isDone()) continue;
                    var delta = completion.join();
                    var after = GraphUpdates.apply(state, delta);
                    if (index > 0 && !reset && (NodeGuard.failed(after) || after.workflow().status() == WorkflowStatus.RETRYING)) {
                        reset = true; return Data.of(output("TEXT_RESET", ""));
                    }
                    return Data.done(delta);
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); closed.set(true); return Data.error(e); }
            catch (CompletionException e) { return Data.error(e.getCause()); }
        }
        private NodeOutput<AssistantState> output(String type, String text) {
            var delta = GraphUpdates.event(state, WorkflowStatus.RUNNING, type, type, type.equals("TEXT_RESET") ? "本次临时回答已撤销。" : "",
                    Map.of("text", text, "attemptId", attemptId, "transient", true));
            var output = (AssistantState.OutputContext) delta.get(AssistantState.OUTPUT);
            var data = new LinkedHashMap<>(output.data());
            data.put("sequence", ++index); data.put("eventId", state.request().requestId() + ":" + state.plan().step().stepId() + ":" + attemptId + ":" + index);
            delta.put(AssistantState.OUTPUT, new AssistantState.OutputContext(type, type, output.message(), data));
            return NodeOutput.of(node, GraphUpdates.apply(state, delta));
        }
    }
}
