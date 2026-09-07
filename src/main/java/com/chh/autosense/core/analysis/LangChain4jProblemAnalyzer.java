package com.chh.autosense.core.analysis;

import com.chh.autosense.ai.factory.AiFailureMapping;
import com.chh.autosense.ai.factory.ProblemAnalysisServiceFactory;
import com.chh.autosense.ai.model.ProblemAnalysis;
import com.chh.autosense.core.session.memory.ConversationHistorySnapshot;
import com.chh.autosense.exception.ApiException;
import com.chh.autosense.exception.ErrorCode;
import com.chh.autosense.utils.PromptInputEncoder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Real-mode problem analysis through the actual AI service proxy. */
@Component
@ConditionalOnProperty(name = "autosense.llm.mode", havingValue = "real")
@Slf4j
public class LangChain4jProblemAnalyzer implements ProblemAnalyzer {

    private final ProblemAnalysisServiceFactory problemAnalysisServiceFactory;
    private final PromptInputEncoder encoder;

    public LangChain4jProblemAnalyzer(ProblemAnalysisServiceFactory problemAnalysisServiceFactory,
                                      PromptInputEncoder encoder) {
        this.problemAnalysisServiceFactory = problemAnalysisServiceFactory;
        this.encoder = encoder;
    }

    @Override
    public ProblemAnalysis analyze(String text, ConversationHistorySnapshot history) {
        long started = System.nanoTime();
        try {
            ProblemAnalysis analysis = problemAnalysisServiceFactory.problemAnalysisService()
                    .analyze(encoder.history(history), encoder.text(text));
            log.info("AI call completed: operation=problem-analysis, elapsedMs={}",
                    (System.nanoTime() - started) / 1_000_000);
            if (analysis == null) {
                return new ProblemAnalysis(null, null, null, false,
                        "没能理解您的问题，请换一种方式描述设备与故障现象。");
            }
            return analysis;
        } catch (RuntimeException e) {
            if (AiFailureMapping.isStructureFailure(e) && !AiFailureMapping.isTransportFailure(e)) {
                log.warn("AI call rejected: operation=problem-analysis, reasonCode=MODEL_OUTPUT_UNPARSEABLE");
                return new ProblemAnalysis(null, null, null, false,
                        "没能理解您的问题，请换一种方式描述设备与故障现象。");
            }
            log.warn("AI call failed: operation=problem-analysis, elapsedMs={}, errorType={}",
                    (System.nanoTime() - started) / 1_000_000, e.getClass().getSimpleName());
            throw new ApiException(ErrorCode.AI_SERVICE_UNAVAILABLE, "助手服务暂时不可用，请稍后再试。");
        }
    }
}
