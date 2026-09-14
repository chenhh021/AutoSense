package com.chh.autosense.ai.model;
import com.chh.autosense.ai.model.enums.KnowledgeMissingInformation;
import java.util.Set;
/** Applicability gaps do not create a prerequisite clarification state. */
public record KnowledgeQueryAnalysis(String deviceType, String brand, String model, String queryText,
        Set<KnowledgeMissingInformation> missingInformation, String version, String environment) {
    public KnowledgeQueryAnalysis {
        if (queryText == null || queryText.isBlank()) throw new IllegalArgumentException("Knowledge query text is required");
        missingInformation = missingInformation == null ? Set.of() : Set.copyOf(missingInformation);
    }
}
