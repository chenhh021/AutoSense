package com.chh.autosense.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * 浏览器跨域访问配置。
 *
 * <p>生产环境应通过 {@code CORS_ALLOWED_ORIGINS} 设置明确的前端站点地址，
 * 不允许使用通配来源。</p>
 */
@ConfigurationProperties(prefix = "autosense.cors")
public record CorsProperties(
        @DefaultValue({
                "http://localhost:3000",
                "http://localhost:5173",
                "http://localhost:8080",
                "http://127.0.0.1:3000",
                "http://127.0.0.1:5173",
                "http://127.0.0.1:8080"
        })
        List<String> allowedOrigins,
        @DefaultValue("3600") long maxAgeSeconds
) {
}
