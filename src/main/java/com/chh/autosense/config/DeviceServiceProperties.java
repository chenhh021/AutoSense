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
    public DeviceServiceProperties {
        if (timeoutSeconds != null && timeoutSeconds <= 0) throw new IllegalArgumentException("Invalid device service timeout");
    }

    /** The same normalized address must be used for identity and for actual requests. */
    public String normalizedBaseUrl() {
        try {
            var uri = new java.net.URI(baseUrl);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
            if (!java.util.Set.of("http", "https").contains(scheme) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getPort() < -1 || uri.getPort() == 0 || uri.getPort() > 65535)
                throw new IllegalArgumentException();
            String path = java.util.Objects.toString(uri.getRawPath(), "");
            for (String segment : path.split("/", -1)) if (segment.equals(".") || segment.equals("..")) throw new IllegalArgumentException();
            path = path.replaceAll("/+$", "");
            int port = uri.getPort();
            if (scheme.equals("http") && port == 80 || scheme.equals("https") && port == 443) port = -1;
            return scheme + "://" + uri.getHost().toLowerCase(java.util.Locale.ROOT) + (port == -1 ? "" : ":" + port) + path;
        } catch (Exception e) { throw new IllegalArgumentException("Invalid device service base address"); }
    }

    public String endpointHash() {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(normalizedBaseUrl().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }
}
