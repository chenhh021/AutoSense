package com.chh.autosense.mapper;

import com.chh.autosense.domain.entity.RepairActionLog;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface RepairActionLogMapper extends BaseMapper<RepairActionLog> {
    @Select("SELECT * FROM repair_action_log WHERE request_id=#{requestId} AND event_key=#{eventKey}")
    RepairActionLog byEventKey(@Param("requestId") String requestId, @Param("eventKey") String eventKey);

    @Select("SELECT * FROM repair_action_log WHERE request_id=#{requestId} AND event_sequence > #{sequence} ORDER BY event_sequence")
    List<RepairActionLog> afterSequence(@Param("requestId") String requestId, @Param("sequence") long sequence);
}
