package com.chh.autosense.core.security;

import com.chh.autosense.config.AuthProperties;
import com.chh.autosense.constant.UserRoleConstants;
import com.chh.autosense.domain.entity.User;
import com.chh.autosense.mapper.UserMapper;
import com.chh.autosense.service.user.AuthTokenService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * 令牌解析(R21,FR-023):真实令牌(Redis,token 与索引共同有效,命中即滚动续期)
 * 与当前启用账号共同校验——账号被禁用/删除即拒绝,角色以数据库当前值为准。
 * Redis 不可用一律拒绝(null)。dev 形式 "user-{id}" 仅 AUTH_DEV_MODE=true 时接受,
 * 角色固定 user,生产必须关闭。
 * 拒绝日志为英文参数化事件,不携带令牌或身份信息。
 */
@Component
@Slf4j
public class UserTokenResolver {

    private final AuthProperties properties;
    private final AuthTokenService tokenService;
    private final UserMapper userMapper;

    public UserTokenResolver(AuthProperties properties, AuthTokenService tokenService,
                             UserMapper userMapper) {
        this.properties = properties;
        this.tokenService = tokenService;
        this.userMapper = userMapper;
    }

    /**
     * @return 解析成功返回 AuthUser,令牌缺失/非法/账号不可用返回 null
     */
    public AuthUser resolve(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return null;
        }
        String token = authorizationHeader.substring("Bearer ".length()).trim();
        // 真实令牌优先(R21):Redis 不可用拒绝,不放行
        AuthUser real;
        try {
            real = tokenService.resolve(token);
        } catch (DataAccessException e) {
            log.warn("Authentication rejected: reasonCode=TOKEN_STORE_UNAVAILABLE");
            return null;
        }
        if (real != null) {
            // 账号当前状态复核:禁用/删除即拒绝;角色以数据库为准,防止旧令牌携带过期角色
            User account;
            try {
                account = userMapper.selectByIdIncludingDeleted(real.userId());
            } catch (DataAccessException e) {
                log.warn("Authentication rejected: reasonCode=USER_STORE_UNAVAILABLE");
                return null;
            }
            if (account == null || (account.getIsDelete() != null && account.getIsDelete() != 0)) {
                log.warn("Authentication rejected: reasonCode=ACCOUNT_DISABLED");
                return null;
            }
            return new AuthUser(account.getId(), account.getUserRole());
        }
        if (properties.devMode() && token.startsWith("user-")) {
            try {
                return new AuthUser(Long.parseLong(token.substring("user-".length())),
                        UserRoleConstants.USER);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
