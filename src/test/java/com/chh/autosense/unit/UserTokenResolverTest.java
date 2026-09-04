package com.chh.autosense.unit;

import com.chh.autosense.config.AuthProperties;
import com.chh.autosense.security.AuthUser;
import com.chh.autosense.security.UserTokenResolver;
import com.chh.autosense.user.AuthTokenService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T044:UserTokenResolver 单测(R21)——真实令牌优先;dev 令牌仅在
 * AUTH_DEV_MODE=true 时生效;dev 令牌角色固定 user。
 */
class UserTokenResolverTest {

    private final AuthTokenService tokenService = mock(AuthTokenService.class);

    private UserTokenResolver resolver(boolean devMode) {
        return new UserTokenResolver(new AuthProperties(null, devMode, 7), tokenService);
    }

    @Test
    void 真实令牌优先于dev形式() {
        // "user-12" 同时是合法 dev 形式;但真实令牌命中时应以真实令牌为准
        when(tokenService.resolve("user-12")).thenReturn(new AuthUser(99L, "admin"));

        AuthUser user = resolver(true).resolve("Bearer user-12");

        assertThat(user.userId()).isEqualTo(99L);
        assertThat(user.role()).isEqualTo("admin");
    }

    @Test
    void 真实令牌解析并携带角色() {
        when(tokenService.resolve("real-token")).thenReturn(new AuthUser(12L, "user"));

        AuthUser user = resolver(false).resolve("Bearer real-token");

        assertThat(user.userId()).isEqualTo(12L);
        assertThat(user.role()).isEqualTo("user");
    }

    @Test
    void dev模式开启时接受user前缀令牌() {
        AuthUser user = resolver(true).resolve("Bearer user-12");

        assertThat(user.userId()).isEqualTo(12L);
        assertThat(user.role()).isEqualTo("user");
    }

    @Test
    void dev模式关闭时拒绝user前缀令牌() {
        assertThat(resolver(false).resolve("Bearer user-12")).isNull();
    }

    @Test
    void 禁用后令牌已删除则解析失败() {
        // 真实令牌未命中(Redis 已删)且 dev 关闭
        when(tokenService.resolve("revoked-token")).thenReturn(null);

        assertThat(resolver(false).resolve("Bearer revoked-token")).isNull();
    }

    @Test
    void 非法输入返回null() {
        UserTokenResolver r = resolver(true);
        assertThat(r.resolve(null)).isNull();
        assertThat(r.resolve("Basic abc")).isNull();
        assertThat(r.resolve("Bearer user-abc")).isNull();
    }
}
