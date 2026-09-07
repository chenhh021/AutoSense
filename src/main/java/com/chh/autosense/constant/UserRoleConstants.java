package com.chh.autosense.constant;

/**
 * 全局角色常量（user-device-api §2）：唯一权威来源，禁止各层各自硬编码。
 * 不新增角色或权限等级。
 */
public final class UserRoleConstants {

    /** 普通用户：注册默认角色，只能访问本人资源。 */
    public static final String USER = "user";

    /** 管理员：可访问用户管理端点，但仍只能访问本人设备与会话。 */
    public static final String ADMIN = "admin";

    private UserRoleConstants() {
    }
}
