package com.chh.autosense.core.routing;

import com.chh.autosense.ai.model.enums.DiagnosisMode;
import com.chh.autosense.core.security.AuthUser;
import com.chh.autosense.core.session.memory.ConversationHistorySnapshot;
import com.chh.autosense.domain.enums.AssistantCapability;
import com.chh.autosense.domain.enums.SessionStatus;
import java.time.LocalDateTime;
import java.util.Objects;

/** Identity and processing boundaries come from the server. Confirmation is only input intent. */
public record CapabilityRequest(AuthUser user, long sessionId, long reportId, int round, long messageId,
        String content, Boolean confirmRepair, ConversationHistorySnapshot history, LocalDateTime deadline,
        AssistantCapability capability, DiagnosisMode diagnosisMode, String targetHint, SessionStatus waitingState,
        Boolean requiresKnowledgeBase) {
    public CapabilityRequest {
        Objects.requireNonNull(user); Objects.requireNonNull(history); Objects.requireNonNull(capability);
        if (history.sessionId() != sessionId || history.beforeMessageId() != messageId) {
            throw new IllegalArgumentException("History does not match the accepted message");
        }
    }
}
