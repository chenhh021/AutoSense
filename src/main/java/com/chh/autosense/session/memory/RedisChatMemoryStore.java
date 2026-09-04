package com.chh.autosense.session.memory;

import com.chh.autosense.domain.model.ChatMessage;
import com.chh.autosense.repository.ChatMessageMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 对话记忆存储(R15,FR-018):Redis 热窗口(键 autosense:chatmemory:{sessionId},
 * 30min 滚动 TTL);miss 时从 MySQL chat_message 重建(长期保留,取最近 N 条由
 * MessageWindowChatMemory 截断)。章程键规范:前缀 + TTL。
 */
@Component
public class RedisChatMemoryStore implements ChatMemoryStore {

    private static final String KEY_PREFIX = "autosense:chatmemory:";
    private static final Duration TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redis;
    private final ChatMessageMapper chatMessageMapper;

    public RedisChatMemoryStore(StringRedisTemplate redis, ChatMessageMapper chatMessageMapper) {
        this.redis = redis;
        this.chatMessageMapper = chatMessageMapper;
    }

    @Override
    public List<dev.langchain4j.data.message.ChatMessage> getMessages(Object memoryId) {
        String key = key(memoryId);
        List<String> cached = redis.opsForList().range(key, 0, -1);
        if (cached != null && !cached.isEmpty()) {
            redis.expire(key, TTL);
            return cached.stream().map(ChatMessageDeserializer::messageFromJson).toList();
        }
        // 冷启动:从 MySQL 长期留存中重建(R15)
        List<ChatMessage> rows = chatMessageMapper.selectListByQuery(
                com.mybatisflex.core.query.QueryWrapper.create()
                        .where("session_id = ?", memoryId)
                        .orderBy("created_at", false)
                        .orderBy("id", false)
                        .limit(100));
        // 查询为倒序取最近 100 条,翻转为时间正序(窗口截断由 MessageWindowChatMemory 负责)
        List<dev.langchain4j.data.message.ChatMessage> rebuilt = rows.stream()
                .sorted(java.util.Comparator.comparing(ChatMessage::getCreatedAt)
                        .thenComparing(ChatMessage::getId))
                .map(this::toLc4j)
                .toList();
        if (!rebuilt.isEmpty()) {
            updateMessages(memoryId, rebuilt);
        }
        return rebuilt;
    }

    @Override
    public void updateMessages(Object memoryId, List<dev.langchain4j.data.message.ChatMessage> messages) {
        String key = key(memoryId);
        redis.delete(key);
        if (messages != null && !messages.isEmpty()) {
            redis.opsForList().rightPushAll(key,
                    messages.stream().map(ChatMessageSerializer::messageToJson).toList());
        }
        redis.expire(key, TTL);
    }

    @Override
    public void deleteMessages(Object memoryId) {
        redis.delete(key(memoryId));
    }

    private String key(Object memoryId) {
        return KEY_PREFIX + memoryId;
    }

    private dev.langchain4j.data.message.ChatMessage toLc4j(ChatMessage row) {
        return switch (row.getRole()) {
            case "USER" -> dev.langchain4j.data.message.UserMessage.from(row.getContent());
            case "ASSISTANT" -> dev.langchain4j.data.message.AiMessage.from(row.getContent());
            default -> dev.langchain4j.data.message.SystemMessage.from(row.getContent());
        };
    }
}
