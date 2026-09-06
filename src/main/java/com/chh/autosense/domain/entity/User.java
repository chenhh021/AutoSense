package com.chh.autosense.domain.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户(data-model.md §10,FR-022~027)。列为 camelCase(用户给定 DDL,R22),
 * 无法走默认驼峰转下划线映射,每字段显式 @Column。
 * isDelete 为逻辑删除标记:禁用即逻辑删除,MyBatis-Flex 自动过滤/更新。
 */
@Data
@Table("user")
public class User {

    @Id(keyType = KeyType.Auto)
    private Long id;

    @Column("userAccount")
    private String userAccount;

    /** BCrypt 散列;任何接口/日志不得输出(R20) */
    @Column("userPassword")
    private String userPassword;

    @Column("userName")
    private String userName;

    @Column("userAvatar")
    private String userAvatar;

    @Column("userProfile")
    private String userProfile;

    /** user / admin(FR-026) */
    @Column("userRole")
    private String userRole;

    @Column("editTime")
    private LocalDateTime editTime;

    @Column("createTime")
    private LocalDateTime createTime;

    @Column("updateTime")
    private LocalDateTime updateTime;

    @Column(value = "isDelete", isLogicDelete = true)
    private Integer isDelete;
}
