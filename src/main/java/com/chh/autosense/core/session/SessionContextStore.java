package com.chh.autosense.core.session;

import com.chh.autosense.config.AssistantProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 会话上下文存取：完整 JSON + SET/TTL 原子替换（不再使用会残留旧字段的 Hash 增量覆盖）。
 * 旧格式、错版本、解析失败或归属不符一律视为上下文失效，不恢复旧控制授权。
 */
@Component
public class SessionContextStore {

    public static final String KEY_PREFIX = "autosense:session:v2:";

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final Duration ttl;

    public SessionContextStore(StringRedisTemplate redis, ObjectMapper json, AssistantProperties properties) {
        this.redis = redis;
        this.json = json;
        this.ttl = Duration.ofSeconds(properties.contextTtlSeconds());
    }

    private String key(Long sessionId) {
        return KEY_PREFIX + sessionId;
    }

    public void save(SessionContext ctx) {
        try {
            redis.opsForValue().set(key(ctx.sessionId()), json.writeValueAsString(ctx), ttl);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Session context serialization failed");
        }
    }

    /** @return 有效快照；旧格式、错版本或归属不符返回 null（按上下文失效处理）。 */
    public SessionContext load(Long sessionId) {
        String raw = redis.opsForValue().get(key(sessionId));
        if (raw == null) {
            return null;
        }
        try {
            SessionContext ctx = json.readValue(raw, SessionContext.class);
            if (ctx.version() != SessionContext.CURRENT_VERSION
                    || ctx.sessionId() == null || !ctx.sessionId().equals(sessionId)) {
                return null;
            }
            return ctx;
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException e) {
            return null;
        }
    }

    public void evict(Long sessionId) {
        redis.delete(key(sessionId));
    }
}
