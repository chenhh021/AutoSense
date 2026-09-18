package com.chh.autosense.graph;

import com.chh.autosense.core.session.WorkflowPersistenceService;
import com.chh.autosense.domain.dto.ConclusionDto;
import com.chh.autosense.domain.message.*;
import java.util.Objects;

/** Sends the public graph envelope before legacy events that may close the connection. */
public final class WorkflowEventProjector {
    public void send(WorkflowEvent event, SseEventStream stream) {
        stream.send(new SseEvent("workflow", event));
        long sessionId = event.data().conversationId();
        switch (event.type()) {
            case "TEXT", "TEXT_RESET" -> { } // Legacy clients receive only the committed STEP_RESULT text.
            case "STATUS" -> stream.send(SseEvent.status(sessionId, WorkflowPersistenceService.legacyStatus(event.data().status())));
            case "CLARIFY", "CONFIRM", "AWAITING" -> stream.awaitUser(sessionId, event.message());
            case "CONCLUSION" -> stream.conclude(new ConclusionDto("ANSWERED", event.message(), null, null, null, null));
            case "ERROR" -> stream.error(event.code(), event.message(), sessionId);
            case "STEP_RESULT" -> stream.send(SseEvent.token(Objects.toString(event.data().payload().get("answer"), event.message())));
            default -> { }
        }
    }
}
