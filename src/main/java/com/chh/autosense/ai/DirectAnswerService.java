package com.chh.autosense.ai;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public interface DirectAnswerService {
    @SystemMessage(fromResource = "/prompt/direct-answer.txt")
    @UserMessage(fromResource = "/prompt/conversation-input.txt")
    TokenStream answer(@V("history") String history, @V("text") String text);
}
