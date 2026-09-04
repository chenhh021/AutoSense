package com.chh.autosense.repository;

import com.chh.autosense.domain.model.ChatMessage;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {
}
