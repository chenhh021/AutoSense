package com.chh.autosense.mapper;

import com.chh.autosense.domain.entity.WorkflowExecution;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface WorkflowExecutionMapper extends BaseMapper<WorkflowExecution> {
    @Select("SELECT UTC_TIMESTAMP(6)")
    java.time.LocalDateTime databaseNow();

    @Select("SELECT * FROM workflow_execution WHERE request_id=#{requestId} FOR UPDATE")
    WorkflowExecution lock(@Param("requestId") String requestId);

    @Select("SELECT * FROM workflow_execution WHERE session_id=#{sessionId} ORDER BY created_at DESC")
    List<WorkflowExecution> forConversation(@Param("sessionId") long sessionId);

    @Update("UPDATE workflow_execution SET suspended_status=status, status='WAITING_RESUME', lease_owner=NULL, "
            + "lease_until=NULL, fence=fence+1, version=version+1, updated_at=NOW(6) "
            + "WHERE status NOT IN ('COMPLETED','FAILED','REJECTED','CANCELLED','WAITING_RESUME')")
    int suspendAfterRestart();

    @Update("UPDATE workflow_execution SET lease_owner=#{owner}, lease_until=TIMESTAMPADD(SECOND,#{seconds},UTC_TIMESTAMP(6)), "
            + "fence=fence+1, version=version+1 WHERE request_id=#{requestId} AND version=#{version} "
            + "AND (lease_owner IS NULL OR lease_until < UTC_TIMESTAMP(6))")
    int claim(@Param("requestId") String requestId, @Param("version") long version,
              @Param("owner") String owner, @Param("seconds") int seconds);

    @Update("UPDATE workflow_execution SET lease_owner=NULL, lease_until=NULL WHERE request_id=#{requestId} "
            + "AND fence=#{fence} AND lease_owner=#{owner}")
    int releaseClaim(@Param("requestId") String requestId, @Param("fence") long fence, @Param("owner") String owner);
}
