package com.chh.autosense.core.analysis;

import com.chh.autosense.ai.factory.DiagnosisReasonerServiceFactory;
import com.chh.autosense.ai.model.DiagnosisConclusion;
import com.chh.autosense.core.session.memory.ConversationHistorySnapshot;
import com.chh.autosense.exception.ApiException;
import com.chh.autosense.exception.ErrorCode;
import com.chh.autosense.utils.PromptInputEncoder;
import com.chh.autosense.utils.AiCallLog;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Real-mode diagnosis reasoning through the actual AI service proxy. */
@Component
@ConditionalOnProperty(name = "autosense.llm.mode", havingValue = "real")
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
        AiCallLog call = AiCallLog.start("diagnosis-reasoner");
        try {
            String historyJson = encoder.history(history);
            String textJson = encoder.text(text);
            String symptomJson = encoder.symptom(symptom);
            String diagnosticsJson = encoder.diagnostics(diagnostics);
            call.phase(AiCallLog.Phase.SERVICE_SETUP);
            var service = diagnosisReasonerServiceFactory.diagnosisReasonerService();
            call.phase(AiCallLog.Phase.MODEL_INVOCATION);
            DiagnosisConclusion conclusion = service.diagnose(historyJson, textJson, symptomJson, diagnosticsJson);
            if (conclusion == null) {
                throw new ApiException(ErrorCode.AI_SERVICE_UNAVAILABLE, "助手服务暂时不可用，请稍后再试。");
            }
            call.completed();
            return conclusion;
        } catch (ApiException e) {
            call.failed(e);
            throw e;
        } catch (RuntimeException e) {
            call.failed(e);
            throw new ApiException(ErrorCode.AI_SERVICE_UNAVAILABLE, "助手服务暂时不可用，请稍后再试。");
        }
    }
}
