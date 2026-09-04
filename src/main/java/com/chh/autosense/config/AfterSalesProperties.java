package com.chh.autosense.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 售后网点查询配置(research R8/R14);mock-enabled=true 时使用项目内固定 mock 数据,
 * 后续接入地图搜索工具时置 false 并提供 base-url/api-key。
 */
@ConfigurationProperties(prefix = "autosense.aftersales")
public record AfterSalesProperties(
        String baseUrl,
        String apiKey,
        Integer defaultRadiusMeters,
        Integer timeoutSeconds,
        boolean mockEnabled,
        String officialHotlineName,
        String officialHotlinePhone
) {
}
