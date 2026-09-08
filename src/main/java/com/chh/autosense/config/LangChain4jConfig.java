package com.chh.autosense.config;

import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;

/**
 * LangChain4j 装配（research R2/R14，章程原则 IV/V）：
 * 外部化同步/流式模型 Bean，显式 maxRetries 并关闭请求/响应原文日志；
 * 四个真实 AI Service 代理由各自的 ServiceFactory（IntentRouterServiceFactory、
 * ProblemAnalysisServiceFactory、DiagnosisReasonerServiceFactory、DirectAnswerServiceFactory）
 * 装配并完成对应 prompt 资源本地校验，调用方经工厂方法新建代理，不共享单例。
 * 固定系统规则与用户包装全部位于 src/main/resources/prompt/ 资源，
 * 不再保留内联文本块、低层直调或手工 JSON 解析旁路；real 配置失败不会切换到 mock。
 * 仅当 autosense.llm.mode=real 时装配。
 */
@Configuration
@Slf4j
@ConditionalOnProperty(name = "autosense.llm.mode", havingValue = "real")
public class LangChain4jConfig {

    @Bean
    public OpenAiChatModel chatModel(LlmProperties props) {
        log.info("AI model configured: provider=OPENAI_COMPATIBLE, streaming=false, timeoutSeconds={}, maxRetries={}",
                props.timeoutSeconds(), props.maxRetries());
        return OpenAiChatModel.builder()
                .baseUrl(props.baseUrl())
                .apiKey(props.apiKey())
                .modelName(props.modelName())
                .temperature(props.temperature())
                .timeout(Duration.ofSeconds(props.timeoutSeconds()))
                .maxRetries(props.maxRetries())
                .logRequests(false)
                .logResponses(false)
                .build();
    }

    @Bean
    public OpenAiStreamingChatModel streamingChatModel(LlmProperties props) {
        log.info("AI model configured: provider=OPENAI_COMPATIBLE, streaming=true, timeoutSeconds={}",
                props.timeoutSeconds());
        return OpenAiStreamingChatModel.builder()
                .baseUrl(props.baseUrl())
                .apiKey(props.apiKey())
                .modelName(props.modelName())
                .temperature(props.temperature())
                .timeout(Duration.ofSeconds(props.timeoutSeconds()))
                .logRequests(false)
                .logResponses(false)
                .build();
    }
}
