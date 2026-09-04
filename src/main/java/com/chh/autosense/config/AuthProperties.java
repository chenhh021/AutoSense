package com.chh.autosense.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 鉴权配置(FR-015/023,2026-08-29 增加令牌 TTL):dev 模式接受 "user-{id}"
 * 令牌(AUTH_DEV_MODE 显式开关,生产必须 false);真实令牌为 Redis 不透明
 * 随机串,tokenTtlDays 控制滚动有效期(R19)。
 */
@ConfigurationProperties(prefix = "autosense.auth")
public record AuthProperties(
        String issuerUri,
        boolean devMode,
        @DefaultValue("7") long tokenTtlDays
) {
}
