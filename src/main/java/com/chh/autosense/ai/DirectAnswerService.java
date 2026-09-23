package com.chh.autosense.ai;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public interface DirectAnswerService {
    @SystemMessage(fromResource = "/prompt/device-query-answer.txt")
    @UserMessage(fromResource = "/prompt/device-query-input.txt")
    TokenStream answerDeviceQuery(@V("history") String history, @V("text") String text,
                                 @V("instruction") String instruction, @V("queryResult") String queryResult);

    @SystemMessage(fromResource = "/prompt/device-context-answer.txt")
    @UserMessage(fromResource = "/prompt/device-context-input.txt")
    TokenStream answerDeviceContext(@V("history") String history, @V("text") String text,
                                    @V("deviceContext") String deviceContext);

    @SystemMessage(fromResource = "/prompt/knowledge-direct-answer.txt")
    @UserMessage(fromResource = "/prompt/knowledge-direct-input.txt")
    TokenStream answerKnowledge(@V("history") String history, @V("text") String text,
                                @V("answerContext") String answerContext);

    @SystemMessage(fromResource = "/prompt/direct-answer.txt")
    @UserMessage(fromResource = "/prompt/conversation-input.txt")
    TokenStream answer(@V("history") String history, @V("text") String text);
}
