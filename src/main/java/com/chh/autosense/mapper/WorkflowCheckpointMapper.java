package com.chh.autosense.mapper;

import com.chh.autosense.domain.entity.WorkflowCheckpoint;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface WorkflowCheckpointMapper extends BaseMapper<WorkflowCheckpoint> {

    @Select("SELECT * FROM workflow_checkpoint WHERE thread_id=#{threadId} ORDER BY version DESC")
    List<WorkflowCheckpoint> history(@Param("threadId") String threadId);

    @Select("SELECT * FROM workflow_checkpoint WHERE thread_id=#{threadId} ORDER BY version DESC LIMIT 1")
    WorkflowCheckpoint latest(@Param("threadId") String threadId);

    @Select("SELECT * FROM workflow_checkpoint WHERE thread_id=#{threadId} AND checkpoint_id=#{checkpointId}")
    WorkflowCheckpoint find(@Param("threadId") String threadId, @Param("checkpointId") String checkpointId);
}

