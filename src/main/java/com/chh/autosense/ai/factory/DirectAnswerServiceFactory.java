package com.chh.autosense.ai.factory;

import com.chh.autosense.ai.DirectAnswerService;
import com.chh.autosense.utils.AiServiceValidator;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * DirectAnswerService 流式代理工厂:装配后完成 prompt 资源本地校验(失败即中止启动,
 * 零模型请求,不回落内联模板);每次调用新建代理,不共享单例。
 * 仅当 autosense.llm.mode=real 时装配。
 */
@Component
@ConditionalOnProperty(name = "autosense.llm.mode", havingValue = "real")
public class DirectAnswerServiceFactory {

    @Resource
    private StreamingChatModel streamingChatModel;

    @PostConstruct
    public void validate() {
        AiServiceValidator.validateServices(List.of(DirectAnswerService.class));
    }

    public DirectAnswerService directAnswerService() {
        return AiServices.builder(DirectAnswerService.class)
                .streamingChatModel(streamingChatModel)
                .build();
    }
}
