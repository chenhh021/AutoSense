package com.chh.autosense.mapper;

import com.chh.autosense.domain.entity.WorkflowApproval;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface WorkflowApprovalMapper extends BaseMapper<WorkflowApproval> {

    @Select("SELECT * FROM workflow_approval WHERE approval_id=#{approvalId} FOR UPDATE")
    WorkflowApproval lock(@Param("approvalId") String approvalId);

    @Select("SELECT * FROM workflow_approval WHERE request_id=#{requestId} AND step_id=#{stepId} "
            + "AND status IN ('PENDING','APPROVED') ORDER BY created_at DESC LIMIT 1")
    WorkflowApproval current(@Param("requestId") String requestId, @Param("stepId") String stepId);

    @Update("UPDATE workflow_approval SET status=#{status}, version=version+1 WHERE request_id=#{requestId} "
            + "AND step_id=#{stepId} AND status IN ('PENDING','APPROVED')")
    int invalidate(@Param("requestId") String requestId, @Param("stepId") String stepId, @Param("status") String status);
}

