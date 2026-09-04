package com.chh.autosense.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RAG 配置(research R4:MySQL 为权威源,Redis 向量索引可选开启)。
 */
@ConfigurationProperties(prefix = "autosense.rag")
public record RagProperties(
        boolean embeddingsEnabled,
        String indexPrefix,
        Integer topK
) {
}
