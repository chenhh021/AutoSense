package com.chh.autosense.ai.factory;

import com.chh.autosense.ai.DiagnosisReasonerService;
import com.chh.autosense.utils.AiServiceValidator;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * DiagnosisReasonerService 代理工厂:装配后完成 prompt 资源本地校验(失败即中止启动,
 * 零模型请求,不回落内联模板);每次调用新建代理,不共享单例。
 * 仅当 autosense.llm.mode=real 时装配。
 */
@Component
@ConditionalOnProperty(name = "autosense.llm.mode", havingValue = "real")
public class DiagnosisReasonerServiceFactory {

    @Resource
    private ChatModel chatModel;

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
