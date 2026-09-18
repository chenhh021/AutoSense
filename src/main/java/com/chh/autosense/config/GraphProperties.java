package com.chh.autosense.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Map;

/** Server-owned budgets. Plans and HTTP input cannot override these values. */
@ConfigurationProperties("autosense.graph")
public record GraphProperties(
        @DefaultValue("real") String mode,
        @DefaultValue("8") int maxPlanSteps,
        @DefaultValue("30") int plannerTimeoutSeconds,
        Map<String, Integer> stepTimeoutSeconds,
        @DefaultValue("2") int maxRetries,
        @DefaultValue("1000") long retryDelayMillis,
        @DefaultValue("300") int approvalTtlSeconds,
        @DefaultValue("30") int expensiveStepThresholdSeconds,
        @DefaultValue("300") int executionSliceTimeoutSeconds,
        @DefaultValue("256") int maxGraphIterations) {

    public GraphProperties {
        if (!"real".equals(mode) && !"stub".equals(mode)) {
            throw new IllegalArgumentException("Invalid graph mode");
        }
        if (maxPlanSteps < 1 || maxPlanSteps > 64 || plannerTimeoutSeconds < 1
                || maxRetries < 0 || maxRetries > 10 || retryDelayMillis < 0
                || approvalTtlSeconds < 1 || expensiveStepThresholdSeconds < 1
                || executionSliceTimeoutSeconds < 1 || maxGraphIterations < 8) {
            throw new IllegalArgumentException("Invalid graph execution budget");
        }
        var values = new java.util.HashMap<>(Map.of(
                "KNOWLEDGE_CONSULT", 60, "FAULT_DIAGNOSIS", 60,
                "DEVICE_QUERY", 10, "DEVICE_CONTROL", 10));
        if (stepTimeoutSeconds != null) {
            stepTimeoutSeconds.forEach((key, value) -> {
                if (!values.containsKey(key) || value == null || value < 1) {
                    throw new IllegalArgumentException("Invalid graph step timeout");
                }
                values.put(key, value);
            });
        }
        stepTimeoutSeconds = Map.copyOf(values);
    }

    public Duration stepTimeout(String type) {
        Integer seconds = stepTimeoutSeconds.get(type);
        if (seconds == null) throw new IllegalArgumentException("Unknown graph step type");
        return Duration.ofSeconds(seconds);
    }
}
