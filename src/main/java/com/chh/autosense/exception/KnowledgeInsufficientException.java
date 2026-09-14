package com.chh.autosense.exception;
import com.chh.autosense.domain.enums.KnowledgeDirectAnswerReason;
/** Expected fallback without provider responses, evidence or exception causes. */
public final class KnowledgeInsufficientException extends RuntimeException {
    private final KnowledgeDirectAnswerReason reason;
    public KnowledgeInsufficientException(KnowledgeDirectAnswerReason reason) {
        super("Knowledge retrieval insufficient: reason=" + reason, null, false, false);
        if (reason != KnowledgeDirectAnswerReason.NO_MATCH && reason != KnowledgeDirectAnswerReason.LOW_RELEVANCE)
            throw new IllegalArgumentException("Invalid retrieval insufficiency reason");
        this.reason = reason;
    }
    public KnowledgeDirectAnswerReason reason() { return reason; }
}
