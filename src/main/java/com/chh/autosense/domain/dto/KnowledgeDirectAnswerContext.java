package com.chh.autosense.domain.dto;
import com.chh.autosense.domain.enums.KnowledgeDirectAnswerReason;
import java.util.Objects;
public record KnowledgeDirectAnswerContext(KnowledgeDirectAnswerReason reason) {
    public KnowledgeDirectAnswerContext { Objects.requireNonNull(reason); }
}
