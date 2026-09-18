package com.chh.autosense.graph;

import com.chh.autosense.domain.enums.*;
import com.chh.autosense.domain.message.WorkflowEvent;
import com.chh.autosense.graph.state.AssistantState;
import org.bsc.langgraph4j.NodeOutput;
import java.util.*;
import java.util.function.Consumer;

/** A connection-scoped projection. Wait notifications are released only after graph interruption completes. */
public final class WorkflowEventStream {
    public void consume(Iterable<? extends NodeOutput<AssistantState>> stream, Consumer<WorkflowEvent> sink) {
        var seen = new HashSet<String>();
        WorkflowEvent pendingWait = null;
        for (var output : stream) {
            var value = output.state().output();
            if (value.type().isBlank()) continue;
            var event = project(value);
            if (!seen.add(event.data().eventId())) continue;
            if (event.data().status() == WorkflowStatus.WAITING_APPROVAL || event.data().status() == WorkflowStatus.WAITING_INPUT) {
                pendingWait = event;
            } else {
                pendingWait = null;
                sink.accept(event);
            }
        }
        // Iteration failure never publishes a false durable wait.
        if (pendingWait != null) sink.accept(pendingWait);
    }

    @SuppressWarnings("unchecked")
    public WorkflowEvent project(AssistantState.OutputContext output) {
        Map<String, Object> data = output.data();
        Map<String, Object> progress = (Map<String, Object>) data.get("progress");
        var projected = new WorkflowEvent.Data((String) data.get("eventId"), number(data, "sequence"),
                (String) data.get("requestId"), number(data, "conversationId"), (String) data.get("stepId"),
                data.get("stepType") == null ? null : PlanStepType.valueOf((String) data.get("stepType")),
                WorkflowStatus.valueOf((String) data.get("status")), new AssistantState.Progress((int) number(progress, "total"),
                (int) number(progress, "completed"), (int) number(progress, "skipped"), (int) number(progress, "notExecuted")),
                number(data, "version"), (Map<String, Object>) data.get("payload"));
        return new WorkflowEvent(output.type(), output.code(), output.message(), projected);
    }
    private long number(Map<String, Object> map, String name) { return ((Number) map.get(name)).longValue(); }
}
