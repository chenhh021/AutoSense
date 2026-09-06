package com.chh.autosense.core.session;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 设备互斥锁(FR-016,R6):键 autosense:lock:device:{deviceId},
 * SET NX PX 10 分钟,状态机推进时续期,终态释放。
 */
@Component
public class DeviceLockService {

    public static final String KEY_PREFIX = "autosense:lock:device:";
    public static final Duration TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redis;

    public DeviceLockService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private String key(Long deviceId) {
        return KEY_PREFIX + deviceId;
    }

    /**
     * 尝试加锁;成功返回 true,设备已有进行中会话返回 false。
     */
    public boolean tryLock(Long deviceId, Long sessionId) {
        Boolean ok = redis.opsForValue()
                .setIfAbsent(key(deviceId), String.valueOf(sessionId), TTL);
        return Boolean.TRUE.equals(ok);
    }

    public void renew(Long deviceId) {
        redis.expire(key(deviceId), TTL);
    }

    public void release(Long deviceId, Long sessionId) {
        String holder = redis.opsForValue().get(key(deviceId));
        if (String.valueOf(sessionId).equals(holder)) {
            redis.delete(key(deviceId));
        }
    }
}
