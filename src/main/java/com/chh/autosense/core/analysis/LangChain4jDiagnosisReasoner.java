package com.chh.autosense.core.analysis;

import com.chh.autosense.ai.factory.AiFailureMapping;
import com.chh.autosense.ai.factory.DiagnosisReasonerServiceFactory;
import com.chh.autosense.ai.model.DiagnosisConclusion;
import com.chh.autosense.core.session.memory.ConversationHistorySnapshot;
import com.chh.autosense.exception.ApiException;
import com.chh.autosense.exception.ErrorCode;
import com.chh.autosense.utils.PromptInputEncoder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Real-mode diagnosis reasoning through the actual AI service proxy. */
@Component
@ConditionalOnProperty(name = "autosense.llm.mode", havingValue = "real")
@Slf4j
public class LangChain4jDiagnosisReasoner implements DiagnosisReasoner {

    private final DiagnosisReasonerServiceFactory diagnosisReasonerServiceFactory;
    private final PromptInputEncoder encoder;

    public LangChain4jDiagnosisReasoner(DiagnosisReasonerServiceFactory diagnosisReasonerServiceFactory,
                                        PromptInputEncoder encoder) {
        this.diagnosisReasonerServiceFactory = diagnosisReasonerServiceFactory;
        this.encoder = encoder;
    }

    @Override
    public DiagnosisConclusion diagnose(String text, String symptom, Map<String, Object> diagnostics,
                                        ConversationHistorySnapshot history) {
        long started = System.nanoTime();
        try {
            DiagnosisConclusion conclusion = diagnosisReasonerServiceFactory.diagnosisReasonerService()
                    .diagnose(encoder.history(history), encoder.text(text),
                            encoder.symptom(symptom), encoder.diagnostics(diagnostics));
            log.info("AI call completed: operation=diagnosis-reasoner, elapsedMs={}",
                    (System.nanoTime() - started) / 1_000_000);
            if (conclusion == null) {
                throw new ApiException(ErrorCode.AI_SERVICE_UNAVAILABLE, "助手服务暂时不可用，请稍后再试。");
            }
            return conclusion;
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("AI call failed: operation=diagnosis-reasoner, elapsedMs={}, errorType={}, structural={}",
                    (System.nanoTime() - started) / 1_000_000, e.getClass().getSimpleName(),
                    AiFailureMapping.isStructureFailure(e) && !AiFailureMapping.isTransportFailure(e));
            throw new ApiException(ErrorCode.AI_SERVICE_UNAVAILABLE, "助手服务暂时不可用，请稍后再试。");
        }
    }
}
