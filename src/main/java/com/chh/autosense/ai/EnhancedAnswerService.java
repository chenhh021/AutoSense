package com.chh.autosense.ai;

import com.chh.autosense.ai.model.ProblemAnalysis;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public interface EnhancedAnswerService {
    @SystemMessage(fromResource = "/prompt/knowledge-query-analysis.txt")
    @UserMessage(fromResource = "/prompt/knowledge-query-input.txt")
    com.chh.autosense.ai.model.KnowledgeQueryAnalysis analyzeKnowledge(
            @V("history") String history, @V("text") String text, @V("catalog") String catalog);

    @SystemMessage(fromResource = "/prompt/knowledge-answer.txt")
    @UserMessage(fromResource = "/prompt/knowledge-answer-input.txt")
    dev.langchain4j.service.Result<com.chh.autosense.ai.model.KnowledgeAnswer> answerKnowledge(@V("request") String request);

    @SystemMessage(fromResource = "/prompt/problem-analysis.txt")
    @UserMessage(fromResource = "/prompt/conversation-input.txt")
    ProblemAnalysis analyze(@V("history") String history, @V("text") String text);
}
