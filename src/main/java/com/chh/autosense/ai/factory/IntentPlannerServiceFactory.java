package com.chh.autosense.ai.factory;

import com.chh.autosense.ai.IntentPlannerService;
import com.chh.autosense.utils.AiServiceValidator;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
@RequiredArgsConstructor
public class IntentPlannerServiceFactory {
    private final ChatModel chatModel;
    @PostConstruct public void validate() { AiServiceValidator.validateServices(List.of(IntentPlannerService.class)); }
    public IntentPlannerService intentPlannerService() {
        validate();
        return AiServices.builder(IntentPlannerService.class).chatModel(chatModel).build();
    }
}
