package com.chh.autosense.ai;

import com.chh.autosense.ai.model.ProblemAnalysis;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public interface ProblemAnalysisService {
    @SystemMessage(fromResource = "/prompt/problem-analysis.txt")
    @UserMessage(fromResource = "/prompt/conversation-input.txt")
    ProblemAnalysis analyze(@V("history") String history, @V("text") String text);
}
