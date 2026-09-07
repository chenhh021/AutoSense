package com.chh.autosense.core.routing;

import com.chh.autosense.domain.enums.AssistantCapability;
import com.chh.autosense.exception.ApiException;
import com.chh.autosense.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

@Component
@Slf4j
public class CapabilityDispatcher {
    private final Map<AssistantCapability, AssistantCapabilityHandler> handlers;

    public CapabilityDispatcher(List<AssistantCapabilityHandler> handlers) {
        var registered = new EnumMap<AssistantCapability, AssistantCapabilityHandler>(AssistantCapability.class);
        for (var handler : handlers) {
            if (handler.capability() == null || registered.putIfAbsent(handler.capability(), handler) != null) {
                throw new IllegalStateException("Duplicate or invalid capability registration");
            }
        }
        this.handlers = Map.copyOf(registered);
    }

    public CompletionStage<CapabilityResult> dispatch(CapabilityRequest request, Consumer<String> visibleText) {
        var handler = required(request);
        log.info("Capability dispatched: capability={}", request.capability());
        return handler.handle(request, visibleText);
    }

    public CompletionStage<CapabilityResult> continueWaiting(CapabilityRequest request, Consumer<String> visibleText) {
        return required(request).continueWaiting(request, visibleText);
    }

    private AssistantCapabilityHandler required(CapabilityRequest request) {
        var handler = handlers.get(request.capability());
        if (handler == null) {
            log.warn("Capability unavailable: capability={}", request.capability());
            throw new ApiException(ErrorCode.CAPABILITY_NOT_AVAILABLE, "该能力暂未接入，请稍后再试。", request.sessionId());
        }
        return handler;
    }
}
