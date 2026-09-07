package com.chh.autosense.ai;

import com.chh.autosense.ai.model.RoutingDecision;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public interface IntentRouterService {
    @SystemMessage(fromResource = "/prompt/intent-router.txt")
    @UserMessage(fromResource = "/prompt/conversation-input.txt")
    RoutingDecision classify(@V("history") String history, @V("text") String text);
}
