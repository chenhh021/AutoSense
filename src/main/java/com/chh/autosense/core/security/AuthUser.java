package com.chh.autosense.core.security;

import com.chh.autosense.constant.UserRoleConstants;

/**
 * 已认证用户(FR-015/026,2026-08-29 增加角色)。dev 模式令牌格式 "user-{id}"
 * (角色固定 user);真实令牌经 Redis 解析携带角色(R19/R21)。
 */
public record AuthUser(Long userId, String role) {

    /** 无角色上下文(如内部归属校验)时的便捷构造,缺省 user。 */
    public AuthUser(Long userId) {
        this(userId, UserRoleConstants.USER);
    }

    public boolean isAdmin() {
        return UserRoleConstants.ADMIN.equals(role);
    }
}
