package com.chh.autosense.mapper;

import com.chh.autosense.domain.entity.WorkflowStep;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface WorkflowStepMapper extends BaseMapper<WorkflowStep> {

    @Select("SELECT * FROM workflow_step WHERE request_id=#{requestId} AND step_id=#{stepId} FOR UPDATE")
    WorkflowStep lock(@Param("requestId") String requestId, @Param("stepId") String stepId);

    @Select("SELECT * FROM workflow_step WHERE request_id=#{requestId} ORDER BY ordinal")
    List<WorkflowStep> forWorkflow(@Param("requestId") String requestId);
}

