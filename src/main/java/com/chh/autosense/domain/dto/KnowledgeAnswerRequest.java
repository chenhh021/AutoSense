package com.chh.autosense.domain.dto;
import com.chh.autosense.ai.model.enums.KnowledgeMissingInformation;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
/** Server-produced data envelope; identity and mutable memory never enter a model. */
public record KnowledgeAnswerRequest(List<Map<String, String>> history, String text, String queryText,
                                      Scope scope, long deadlineEpochMillis) {
    public KnowledgeAnswerRequest {
        if (history == null || text == null || queryText == null || queryText.isBlank() || scope == null || deadlineEpochMillis <= 0)
            throw new IllegalArgumentException("Invalid knowledge request");
        history = history.stream().skip(Math.max(0, history.size() - 20L)).map(entry -> {
            if (entry.size() != 2 || !entry.containsKey("role") || !entry.containsKey("content")
                    || !Set.of("USER", "ASSISTANT").contains(entry.get("role")))
                throw new IllegalArgumentException("Invalid visible knowledge history");
            return Map.copyOf(entry);
        }).toList();
    }
    public record Scope(String deviceType, String brand, String model,
            Set<KnowledgeMissingInformation> missingInformation, String version, String environment) {
        public Scope {
            var gaps = new HashSet<>(missingInformation == null ? Set.<KnowledgeMissingInformation>of() : missingInformation);
            if (model == null || model.isBlank()) gaps.add(KnowledgeMissingInformation.MODEL);
            missingInformation = Set.copyOf(gaps);
        }
    }
}
