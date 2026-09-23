package com.chh.autosense.ai.factory;

import com.chh.autosense.ai.IntentPlannerService;
import com.chh.autosense.ai.tools.DeviceListTool;
import com.chh.autosense.utils.AiServiceValidator;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class IntentPlannerServiceFactory {
    private final ChatModel chatModel;

    @Resource
    private DeviceListTool deviceListTool;

    @PostConstruct
    public void validate() {
        AiServiceValidator.validateServices(List.of(IntentPlannerService.class));
    }

    public IntentPlannerService intentPlannerService() {
        validate();
        return AiServices.builder(IntentPlannerService.class)
                .chatModel(new ChatModel() {
                    @Override public dev.langchain4j.model.chat.response.ChatResponse chat(dev.langchain4j.model.chat.request.ChatRequest request) {
                        com.chh.autosense.core.security.PlannerInvocationContext.checkActive();
                        return chatModel.chat(request);
                    }
                    @Override public java.util.Set<dev.langchain4j.model.chat.Capability> supportedCapabilities() {
                        return chatModel.supportedCapabilities();
                    }
                })
                .tools(deviceListTool)
                .build();
    }
}
