package com.chh.autosense.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LLM 配置(章程原则 V:全部经 application.yaml + 环境变量注入)。
 */
@ConfigurationProperties(prefix = "autosense.llm")
public record LlmProperties(
        String baseUrl,
        String apiKey,
        String modelName,
        Double temperature,
        Integer timeoutSeconds,
        /** mock=内置桩(开发);real=装配真实 LangChain4j 模型 */
        String mode
) {
}
