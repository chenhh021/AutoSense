package com.chh.autosense.core.session;

import com.chh.autosense.domain.enums.SessionStatus;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 进行中会话上下文存取(章程原则 III:键带前缀与滚动 TTL)。
 */
@Component
public class SessionContextStore {

    public static final String KEY_PREFIX = "autosense:session:";
    public static final Duration TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redis;

    public SessionContextStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private String key(Long sessionId) {
        return KEY_PREFIX + sessionId;
    }

    public void save(SessionContext ctx) {
        Map<String, String> map = new HashMap<>();
        map.put("sessionId", String.valueOf(ctx.sessionId()));
        map.put("userId", String.valueOf(ctx.userId()));
        map.put("status", ctx.status().name());
        if (ctx.candidateDeviceIds() != null) {
            map.put("candidateDeviceIds", ctx.candidateDeviceIds());
        }
        if (ctx.deviceId() != null) {
            map.put("deviceId", String.valueOf(ctx.deviceId()));
        }
        if (ctx.deviceType() != null) {
            map.put("deviceType", ctx.deviceType());
        }
        if (ctx.pendingActionCode() != null) {
            map.put("pendingActionCode", ctx.pendingActionCode());
        }
        if (ctx.pendingActionParams() != null) {
            map.put("pendingActionParams", ctx.pendingActionParams());
        }
        redis.opsForHash().putAll(key(ctx.sessionId()), map);
        redis.expire(key(ctx.sessionId()), TTL);
    }

    public SessionContext load(Long sessionId) {
        Map<Object, Object> map = redis.opsForHash().entries(key(sessionId));
        if (map == null || map.isEmpty()) {
            return null;
        }
        // 滚动 TTL:读取即续期
        redis.expire(key(sessionId), TTL);
        return new SessionContext(
                Long.valueOf((String) map.get("sessionId")),
                Long.valueOf((String) map.get("userId")),
                SessionStatus.valueOf((String) map.get("status")),
                (String) map.get("candidateDeviceIds"),
                map.get("deviceId") == null ? null : Long.valueOf((String) map.get("deviceId")),
                (String) map.get("deviceType"),
                (String) map.get("pendingActionCode"),
                (String) map.get("pendingActionParams"));
    }

    public void evict(Long sessionId) {
        redis.delete(key(sessionId));
    }
}
