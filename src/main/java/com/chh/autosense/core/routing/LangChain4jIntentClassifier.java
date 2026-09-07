package com.chh.autosense.core.routing;

import com.chh.autosense.ai.factory.AiFailureMapping;
import com.chh.autosense.ai.factory.IntentRouterServiceFactory;
import com.chh.autosense.ai.model.RoutingDecision;
import com.chh.autosense.core.session.memory.ConversationHistorySnapshot;
import com.chh.autosense.exception.ApiException;
import com.chh.autosense.exception.ErrorCode;
import com.chh.autosense.utils.PromptInputEncoder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Real-mode intent classification through the actual AI service proxy. Transport failures
 * become AI_SERVICE_UNAVAILABLE; structural failures return null so the validator maps them
 * to a deterministic clarification. Model output never becomes identity or authorization.
 */
@Component
@ConditionalOnProperty(name = "autosense.llm.mode", havingValue = "real")
@Slf4j
public class LangChain4jIntentClassifier implements IntentClassifier {

    private final IntentRouterServiceFactory intentRouterServiceFactory;
    private final PromptInputEncoder encoder;

    public LangChain4jIntentClassifier(IntentRouterServiceFactory intentRouterServiceFactory,
                                       PromptInputEncoder encoder) {
        this.intentRouterServiceFactory = intentRouterServiceFactory;
        this.encoder = encoder;
    }

    @Override
    public RoutingDecision classify(String text, ConversationHistorySnapshot history) {
        long started = System.nanoTime();
        try {
            RoutingDecision decision = intentRouterServiceFactory.intentRouterService()
                    .classify(encoder.history(history), encoder.text(text));
            log.info("AI call completed: operation=intent-classify, elapsedMs={}", elapsed(started));
            return decision;
        } catch (RuntimeException e) {
            if (AiFailureMapping.isStructureFailure(e) && !AiFailureMapping.isTransportFailure(e)) {
                log.warn("Routing clarification required: reasonCode=MODEL_OUTPUT_UNPARSEABLE");
                return null;
            }
            log.warn("AI call failed: operation=intent-classify, elapsedMs={}, errorType={}",
                    elapsed(started), e.getClass().getSimpleName());
            throw new ApiException(ErrorCode.AI_SERVICE_UNAVAILABLE, "助手服务暂时不可用，请稍后再试。");
        }
    }

    private static long elapsed(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
