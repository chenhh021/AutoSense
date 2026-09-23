package com.chh.autosense.ai;

import com.chh.autosense.ai.model.ExecutionPlanCandidate;
import dev.langchain4j.service.*;

public interface IntentPlannerService {
    @SystemMessage(fromResource = "/prompt/intent-planner.txt")
    @UserMessage(fromResource = "/prompt/planner-input.txt")
    ExecutionPlanCandidate plan(@V("history") String history, @V("text") String text, @V("devices") String devices,
                                @V("originalRequest") String originalRequest);
}
