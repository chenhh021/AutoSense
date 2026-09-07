package com.chh.autosense.mapper;

import com.chh.autosense.domain.entity.RepairSession;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.time.LocalDateTime;

@Mapper
public interface RepairSessionMapper extends BaseMapper<RepairSession> {
    @Select("SELECT * FROM repair_session WHERE id = #{id} FOR UPDATE")
    RepairSession lockById(@Param("id") long id);

    @Select("SELECT CURRENT_TIMESTAMP")
    LocalDateTime databaseNow();
}
