package com.chh.autosense.ai.factory;

import com.chh.autosense.ai.DirectAnswerService;
import com.chh.autosense.utils.AiServiceValidator;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * DirectAnswerService 流式代理工厂:装配后完成 prompt 资源本地校验(失败即中止启动,
 * 零模型请求,不回落内联模板);每次调用新建代理,不共享单例。
 * real/mock 共用同一装配流程，模型由对应配置提供。
 */
@Component
public class DirectAnswerServiceFactory {

    private final StreamingChatModel streamingChatModel;

    public DirectAnswerServiceFactory(StreamingChatModel streamingChatModel) {
        this.streamingChatModel = streamingChatModel;
    }

    @PostConstruct
    public void validate() {
        AiServiceValidator.validateServices(List.of(DirectAnswerService.class));
    }

    public DirectAnswerService directAnswerService() {
        validate();
        return AiServices.builder(DirectAnswerService.class)
                .streamingChatModel(streamingChatModel)
                .build();
    }
}
