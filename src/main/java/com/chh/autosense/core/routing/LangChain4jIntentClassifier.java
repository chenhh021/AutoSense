package com.chh.autosense.core.routing;

import com.chh.autosense.ai.factory.AiFailureMapping;
import com.chh.autosense.ai.factory.IntentRouterServiceFactory;
import com.chh.autosense.ai.model.RoutingDecision;
import com.chh.autosense.core.session.memory.ConversationHistorySnapshot;
import com.chh.autosense.exception.ApiException;
import com.chh.autosense.exception.ErrorCode;
import com.chh.autosense.utils.PromptInputEncoder;
import com.chh.autosense.utils.AiCallLog;
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
        AiCallLog call = AiCallLog.start("intent-classify");
        try {
            String historyJson = encoder.history(history);
            String textJson = encoder.text(text);
            call.phase(AiCallLog.Phase.SERVICE_SETUP);
            var service = intentRouterServiceFactory.intentRouterService();
            call.phase(AiCallLog.Phase.MODEL_INVOCATION);
            RoutingDecision decision = service.classify(historyJson, textJson);
            call.completed();
            return decision;
        } catch (RuntimeException e) {
            call.failed(e);
            if (AiFailureMapping.isStructureFailure(e) && !AiFailureMapping.isTransportFailure(e)) {
                log.warn("Routing clarification required: reasonCode=MODEL_OUTPUT_UNPARSEABLE");
                return null;
            }
            throw new ApiException(ErrorCode.AI_SERVICE_UNAVAILABLE, "助手服务暂时不可用，请稍后再试。");
        }
    }

}
