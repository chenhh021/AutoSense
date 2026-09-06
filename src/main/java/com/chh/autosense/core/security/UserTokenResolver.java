package com.chh.autosense.core.security;

import com.chh.autosense.config.AuthProperties;
import com.chh.autosense.service.user.AuthTokenService;
import org.springframework.stereotype.Component;

/**
 * 令牌解析(R21,FR-023):两级解析——先真实令牌(Redis,AuthTokenService,
 * 命中即滚动续期);未命中且 AUTH_DEV_MODE=true 时才接受 dev 形式
 * "user-{id}"(角色固定 user)。生产必须关闭 dev 模式。
 */
@Component
public class UserTokenResolver {

    private final AuthProperties properties;
    private final AuthTokenService tokenService;

    public UserTokenResolver(AuthProperties properties, AuthTokenService tokenService) {
        this.properties = properties;
        this.tokenService = tokenService;
    }

    /**
     * @return 解析成功返回 AuthUser,令牌缺失/非法返回 null
     */
    public AuthUser resolve(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return null;
        }
        String token = authorizationHeader.substring("Bearer ".length()).trim();
        // 真实令牌优先(R21)
        AuthUser real = tokenService.resolve(token);
        if (real != null) {
            return real;
        }
        if (properties.devMode() && token.startsWith("user-")) {
            try {
                return new AuthUser(Long.parseLong(token.substring("user-".length())), "user");
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
