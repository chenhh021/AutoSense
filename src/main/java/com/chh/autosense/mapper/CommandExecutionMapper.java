package com.chh.autosense.mapper;

import com.chh.autosense.domain.entity.CommandExecution;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface CommandExecutionMapper extends BaseMapper<CommandExecution> {

    @Select("SELECT * FROM command_execution WHERE request_id=#{requestId} AND step_id=#{stepId} FOR UPDATE")
    CommandExecution lockStep(@Param("requestId") String requestId, @Param("stepId") String stepId);

    @Select("SELECT * FROM command_execution WHERE request_id=#{requestId}")
    List<CommandExecution> forWorkflow(@Param("requestId") String requestId);

    @Update("UPDATE command_execution SET status='IN_FLIGHT', certainty='IN_FLIGHT', active_attempt_id=#{attemptId}, "
            + "attempt_count=attempt_count+1, retries_used=#{retriesUsed}, version=version+1, fence=#{fence}, "
            + "started_at=NOW(6), updated_at=NOW(6) WHERE command_id=#{commandId} AND version=#{version} "
            + "AND status IN ('PREPARED','RETRYING')")
    int beginAttempt(@Param("commandId") String commandId, @Param("version") long version, @Param("fence") long fence,
                     @Param("attemptId") String attemptId, @Param("retriesUsed") int retriesUsed);

    @Update("UPDATE command_execution SET status=#{status}, certainty=#{certainty}, result_json=#{result}, "
            + "failure_code=#{failureCode}, version=version+1, finished_at=NOW(6), updated_at=NOW(6) "
            + "WHERE command_id=#{commandId} AND fence=#{fence} AND active_attempt_id=#{attemptId} AND status='IN_FLIGHT'")
    int finishAttempt(@Param("commandId") String commandId, @Param("fence") long fence, @Param("attemptId") String attemptId,
                      @Param("status") String status, @Param("certainty") String certainty, @Param("result") String result,
                      @Param("failureCode") String failureCode);
}

