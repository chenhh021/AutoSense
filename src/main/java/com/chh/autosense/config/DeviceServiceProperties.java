package com.chh.autosense.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * deviceSimulator 配置(research R11:外部已有服务,HTTP 调用,唯一设备交互实现)。
 */
@ConfigurationProperties(prefix = "autosense.device-service")
public record DeviceServiceProperties(
        String baseUrl,
        Integer timeoutSeconds
) {
}
