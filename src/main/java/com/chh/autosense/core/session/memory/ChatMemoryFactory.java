package com.chh.autosense.core.session.memory;

import com.chh.autosense.config.ChatMemoryProperties;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.springframework.stereotype.Component;

/**
 * 会话级 ChatMemory 工厂(R15,FR-018):MessageWindowChatMemory,
 * 窗口大小经 yaml(chat-memory.window,默认 20),memoryId = sessionId。
 */
@Component
public class ChatMemoryFactory {

    private final RedisChatMemoryStore store;
    private final ChatMemoryProperties props;

    public ChatMemoryFactory(RedisChatMemoryStore store, ChatMemoryProperties props) {
        this.store = store;
        this.props = props;
    }

    public ChatMemory forSession(long sessionId) {
        return MessageWindowChatMemory.builder()
                .id(sessionId)
                .maxMessages(props.window() == null ? 20 : props.window())
                .chatMemoryStore(store)
                .build();
    }
}
