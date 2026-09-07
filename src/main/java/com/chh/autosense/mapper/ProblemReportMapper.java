package com.chh.autosense.mapper;

import com.chh.autosense.domain.entity.ProblemReport;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ProblemReportMapper extends BaseMapper<ProblemReport> {
    @Select("SELECT * FROM problem_report WHERE session_id = #{sessionId} ORDER BY round DESC, id DESC LIMIT 1")
    ProblemReport latest(@Param("sessionId") long sessionId);
}
