package com.chh.autosense.ai.model;
import com.chh.autosense.ai.model.enums.KnowledgeAnswerStatus;
import java.util.List;
import java.util.HashSet;
/** Source membership must be checked against the same invocation's Result.sources. */
public record KnowledgeAnswer(String answer, List<String> sourceIds, KnowledgeAnswerStatus status) {
    public KnowledgeAnswer {
        if (answer == null || answer.isBlank() || status == null || sourceIds == null || sourceIds.isEmpty()
                || sourceIds.stream().anyMatch(s -> s == null || s.isBlank())
                || new HashSet<>(sourceIds).size() != sourceIds.size())
            throw new IllegalArgumentException("Invalid knowledge answer");
        sourceIds = List.copyOf(sourceIds);
    }
}
