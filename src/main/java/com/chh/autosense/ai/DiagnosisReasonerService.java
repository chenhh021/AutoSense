package com.chh.autosense.ai;

import com.chh.autosense.ai.model.DiagnosisConclusion;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public interface DiagnosisReasonerService {
    @SystemMessage(fromResource = "/prompt/diagnosis-reasoner.txt")
    @UserMessage(fromResource = "/prompt/diagnosis-input.txt")
    DiagnosisConclusion diagnose(@V("history") String history, @V("text") String text,
                                 @V("symptom") String symptom, @V("diagnostics") String diagnostics);
}
