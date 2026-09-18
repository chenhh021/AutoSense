package com.chh.autosense.ai;

import com.chh.autosense.ai.model.ExecutionPlanCandidate;
import dev.langchain4j.service.*;

public interface IntentPlannerService {
    @SystemMessage(fromResource = "/prompt/intent-planner.txt")
    @UserMessage(fromResource = "/prompt/conversation-input.txt")
    ExecutionPlanCandidate plan(@V("history") String history, @V("text") String text);
}
