package com.chh.autosense.core.routing;

import com.chh.autosense.domain.enums.AssistantCapability;
import com.chh.autosense.exception.ErrorCode;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

public interface AssistantCapabilityHandler {
    AssistantCapability capability();
    CompletionStage<CapabilityResult> handle(CapabilityRequest request, Consumer<String> visibleText);
    default CompletionStage<CapabilityResult> continueWaiting(CapabilityRequest request, Consumer<String> visibleText) {
        return CompletableFuture.completedFuture(CapabilityResult.failure(ErrorCode.CAPABILITY_NOT_AVAILABLE,
                "该能力暂不支持继续处理，请重新提问。"));
    }
}
