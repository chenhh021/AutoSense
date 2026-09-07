package com.chh.autosense.mapper;

import com.chh.autosense.domain.entity.User;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface UserMapper extends BaseMapper<User> {

    /** 恢复被逻辑删除(禁用)的用户;框架逻辑删除条件会拦截常规 update,需原生 SQL。 */
    @Update("UPDATE user SET isDelete = 0 WHERE id = #{id} AND isDelete = 1")
    int restoreById(@Param("id") long id);

    /** 管理端用户列表(含已禁用,isDelete=1);逻辑删除自动条件仅过滤常规查询,此处显式放开。 */
    @Select("""
            SELECT * FROM user
            WHERE (#{keyword} IS NULL OR userAccount LIKE CONCAT('%', #{keyword}, '%')
                   OR userName LIKE CONCAT('%', #{keyword}, '%'))
            ORDER BY id LIMIT #{size} OFFSET #{offset}
            """)
    List<User> selectAllIncludingDeleted(@Param("keyword") String keyword,
                                         @Param("offset") long offset,
                                         @Param("size") long size);

    @Select("""
            SELECT COUNT(*) FROM user
            WHERE (#{keyword} IS NULL OR userAccount LIKE CONCAT('%', #{keyword}, '%')
                   OR userName LIKE CONCAT('%', #{keyword}, '%'))
            """)
    long countAllIncludingDeleted(@Param("keyword") String keyword);

    /** 按账号查找(含已禁用),供注册冲突检查等场景。 */
    @Select("SELECT * FROM user WHERE userAccount = #{account} LIMIT 1")
    User selectByAccountIncludingDeleted(@Param("account") String account);

    /** 按 id 查找(含已禁用),供管理端禁用/启用前定位目标。 */
    @Select("SELECT * FROM user WHERE id = #{id} LIMIT 1")
    User selectByIdIncludingDeleted(@Param("id") long id);

    /** 登录行锁(含已禁用):同账号登录/禁用串行,锁内复核状态。须在事务内调用。 */
    @Select("SELECT * FROM user WHERE userAccount = #{account} LIMIT 1 FOR UPDATE")
    User selectByAccountForUpdate(@Param("account") String account);

    /** 状态变更行锁(含已禁用):同用户禁用/启用串行,锁内复核状态。须在事务内调用。 */
    @Select("SELECT * FROM user WHERE id = #{id} LIMIT 1 FOR UPDATE")
    User selectByIdForUpdate(@Param("id") long id);
}
