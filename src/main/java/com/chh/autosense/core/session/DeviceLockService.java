package com.chh.autosense.core.session;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 设备互斥锁(FR-016,R6):键 autosense:lock:device:{deviceId},
 * SET NX PX 10 分钟。续租/释放均以 Lua 原子校验 owner(sessionId),
 * 错误 owner 续租/释放失败并记录英文参数化冲突日志;租约不构成任何确认语义。
 */
@Component
@Slf4j
public class DeviceLockService {

    public static final String KEY_PREFIX = "autosense:lock:device:";
    public static final Duration TTL = Duration.ofMinutes(10);

    private static final DefaultRedisScript<Long> RENEW_IF_OWNER = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('pexpire', KEYS[1], ARGV[2]) else return 0 end",
            Long.class);

    private static final DefaultRedisScript<Long> RELEASE_IF_OWNER = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

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

    /**
     * 原子 owner 校验续租:仅锁持有者(sessionId)可续租。
     *
     * @return 续租成功 true;锁不存在或 owner 不符 false
     */
    public boolean renew(Long deviceId, Long sessionId) {
        Long ok = redis.execute(RENEW_IF_OWNER, List.of(key(deviceId)),
                String.valueOf(sessionId), Long.toString(TTL.toMillis()));
        if (!Long.valueOf(1).equals(ok)) {
            log.warn("Device lock conflict: operation=renew, result=NOT_OWNER_OR_EXPIRED");
            return false;
        }
        return true;
    }

    /** 原子 owner 校验释放:仅锁持有者可释放,他人释放无效。 */
    public void release(Long deviceId, Long sessionId) {
        Long ok = redis.execute(RELEASE_IF_OWNER, List.of(key(deviceId)),
                String.valueOf(sessionId));
        if (!Long.valueOf(1).equals(ok)) {
            log.warn("Device lock conflict: operation=release, result=NOT_OWNER_OR_EXPIRED");
        }
    }
}
