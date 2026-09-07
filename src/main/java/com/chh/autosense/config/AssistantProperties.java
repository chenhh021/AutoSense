package com.chh.autosense.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("autosense.assistant")
public record AssistantProperties(
        @DefaultValue("120") int processingTimeoutSeconds,
        @DefaultValue("30") int sessionLeaseSeconds,
        @DefaultValue("10") int sessionRenewSeconds,
        @DefaultValue("1800") int contextTtlSeconds) {
    public AssistantProperties {
        if (processingTimeoutSeconds <= 0 || sessionLeaseSeconds <= 0 || sessionRenewSeconds <= 0
                || sessionRenewSeconds >= sessionLeaseSeconds || sessionLeaseSeconds >= processingTimeoutSeconds
                || contextTtlSeconds < processingTimeoutSeconds) {
            throw new IllegalArgumentException("Invalid assistant timing configuration");
        }
    }

    public void validateModelBudget(LlmProperties llm) {
        if ((long) llm.timeoutSeconds() * (llm.maxRetries() + 1L) >= processingTimeoutSeconds) {
            throw new IllegalArgumentException("AI call budget must be shorter than the processing deadline");
        }
    }
}
