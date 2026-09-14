package com.chh.autosense.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;

@ConfigurationProperties("autosense.knowledge.embedding")
public record KnowledgeEmbeddingProperties(
        @DefaultValue("openai-compatible") String provider,
        String baseUrl, String apiKey, String modelName, Integer dimensions,
        @DefaultValue("10") int timeoutSeconds,
        @DefaultValue("0") int maxRetries,
        @DefaultValue("32") int maxSegmentsPerBatch) {

    public KnowledgeEmbeddingProperties {
        if ((!"openai-compatible".equals(provider) && !"mock".equals(provider))
                || (dimensions != null && dimensions <= 0) || timeoutSeconds <= 0
                || maxRetries < 0 || maxRetries > 3 || maxSegmentsPerBatch <= 0) {
            throw new IllegalArgumentException("Invalid knowledge embedding configuration");
        }
    }

    public void validate(LlmProperties llm, AssistantProperties assistant) {
        if ("mock".equals(provider)) {
            if (!"mock".equals(llm.mode())) throw new IllegalArgumentException("Mock embedding requires mock AI mode");
        } else {
            boolean validUrl = false;
            try {
                URI uri = URI.create(baseUrl);
                validUrl = ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                        && uri.getHost() != null && uri.getUserInfo() == null;
            } catch (RuntimeException ignored) {
                // Never include the supplied URL or credentials in a configuration exception.
            }
            if (!validUrl || apiKey == null || apiKey.isBlank() || modelName == null || modelName.isBlank()) {
                throw new IllegalArgumentException("Real knowledge embedding connection is not configured");
            }
        }
        long budget = 3L * llm.timeoutSeconds() * (llm.maxRetries() + 1L)
                + (long) timeoutSeconds * (maxRetries + 1L) + 5;
        if (budget >= assistant.processingTimeoutSeconds()) {
            throw new IllegalArgumentException("Knowledge AI call budget must be shorter than the processing deadline");
        }
    }

    @Override public String toString() { return "KnowledgeEmbeddingProperties[redacted]"; }
}
