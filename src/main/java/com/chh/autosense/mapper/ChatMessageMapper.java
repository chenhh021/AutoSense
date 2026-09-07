package com.chh.autosense.mapper;

import com.chh.autosense.domain.entity.ChatMessage;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {
    @Select("SELECT * FROM chat_message WHERE session_id = #{sessionId} AND id < #{messageId} "
            + "AND role IN ('USER', 'ASSISTANT') ORDER BY id DESC LIMIT 20")
    List<ChatMessage> historyBefore(@Param("sessionId") long sessionId, @Param("messageId") long messageId);
}
