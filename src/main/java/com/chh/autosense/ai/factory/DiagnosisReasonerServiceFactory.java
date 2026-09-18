package com.chh.autosense.ai.factory;

import com.chh.autosense.ai.DiagnosisReasonerService;
import com.chh.autosense.utils.AiServiceValidator;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * DiagnosisReasonerService 代理工厂:装配后完成 prompt 资源本地校验(失败即中止启动,
 * 零模型请求,不回落内联模板);每次调用新建代理,不共享单例。
 * real/mock 模式复用同一工厂，仅模型传输实现不同。
 */
@Component
public class DiagnosisReasonerServiceFactory {

    private final ChatModel chatModel;

    public DiagnosisReasonerServiceFactory(ChatModel chatModel) { this.chatModel = chatModel; }

    @PostConstruct
    public void validate() {
        AiServiceValidator.validateServices(List.of(DiagnosisReasonerService.class));
    }

    public DiagnosisReasonerService diagnosisReasonerService() {
        return AiServices.builder(DiagnosisReasonerService.class)
                .chatModel(chatModel)
                .build();
    }
}
