package com.chh.autosense.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;

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
        @DefaultValue("real") String mode,
        @DefaultValue("0") Integer maxRetries
) {
    public LlmProperties {
        if (!"real".equals(mode) && !"mock".equals(mode)) {
            throw new IllegalArgumentException("Invalid AI mode");
        }
        if (temperature == null || !Double.isFinite(temperature)
                || timeoutSeconds == null || timeoutSeconds <= 0
                || maxRetries == null || maxRetries < 0 || maxRetries > 3) {
            throw new IllegalArgumentException("Invalid AI timing or temperature configuration");
        }
        if ("real".equals(mode)) {
            boolean validUrl = false;
            try {
                URI uri = URI.create(baseUrl);
                validUrl = ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                        && uri.getHost() != null && uri.getUserInfo() == null;
            } catch (RuntimeException ignored) {
                // Configuration failures must not reveal credentials or the input URL.
            }
            if (!validUrl || apiKey == null || apiKey.isBlank()
                    || modelName == null || modelName.isBlank()) {
                throw new IllegalArgumentException("Invalid real AI connection configuration");
            }
        }
    }
}
