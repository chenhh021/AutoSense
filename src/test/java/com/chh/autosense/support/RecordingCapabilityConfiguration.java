package com.chh.autosense.support;

import com.chh.autosense.core.routing.*;
import com.chh.autosense.domain.enums.AssistantCapability;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

/** Test receivers do not read or change devices. Never available to production component scanning. */
@TestConfiguration(proxyBeanMethods = false)
public class RecordingCapabilityConfiguration {
    public static class Recorder {
        public final List<CapabilityRequest> calls = new CopyOnWriteArrayList<>();
        public final Map<AssistantCapability, Function<CapabilityRequest, CompletionStage<CapabilityResult>>> behavior = new ConcurrentHashMap<>();
        public void reset() { calls.clear(); behavior.clear(); }
    }

    @Bean Recorder recorder() { return new Recorder(); }
    @Bean AssistantCapabilityHandler knowledgeReceiver(Recorder r) { return handler(AssistantCapability.KNOWLEDGE, r); }
    @Bean AssistantCapabilityHandler queryReceiver(Recorder r) { return handler(AssistantCapability.DEVICE_QUERY, r); }
    @Bean AssistantCapabilityHandler diagnosisReceiver(Recorder r) { return handler(AssistantCapability.DIAGNOSIS, r); }
    @Bean AssistantCapabilityHandler controlReceiver(Recorder r) { return handler(AssistantCapability.CONTROL, r); }

    private AssistantCapabilityHandler handler(AssistantCapability capability, Recorder recorder) {
        return new AssistantCapabilityHandler() {
            @Override public AssistantCapability capability() { return capability; }
            @Override public CompletionStage<CapabilityResult> handle(CapabilityRequest request, Consumer<String> sink) {
                recorder.calls.add(request);
                var custom = recorder.behavior.get(capability);
                if (custom != null) return custom.apply(request);
                String visible = "test receiver " + capability;
                sink.accept(visible);
                return CompletableFuture.completedFuture(CapabilityResult.answer(visible));
            }
        };
    }
}
