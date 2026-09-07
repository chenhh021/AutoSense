package com.chh.autosense.unit;

import com.chh.autosense.core.session.DeviceLockService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DeviceLockService 单测:加锁互斥;续租/释放经原子脚本按 owner 判定。
 * 原子性证据由 DeviceLockIT 在真实 Redis 上验证,此处仅回归参数传递与结果映射。
 */
class DeviceLockServiceTest {

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final DeviceLockService lockService = new DeviceLockService(redis);

    {
        when(redis.opsForValue()).thenReturn(valueOps);
    }

    @Test
    void 加锁成功与互斥失败() {
        when(valueOps.setIfAbsent(eq("autosense:lock:device:1"), eq("100"),
                any(Duration.class))).thenReturn(true);
        when(valueOps.setIfAbsent(eq("autosense:lock:device:2"), eq("100"),
                any(Duration.class))).thenReturn(false);

        assertThat(lockService.tryLock(1L, 100L)).isTrue();
        assertThat(lockService.tryLock(2L, 100L)).isFalse();
    }

    @SuppressWarnings("unchecked")
    @Test
    void 续租按owner结果映射() {
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString()))
                .thenReturn(1L, 0L);

        assertThat(lockService.renew(1L, 100L)).isTrue();
        assertThat(lockService.renew(1L, 200L)).isFalse();
    }

    @SuppressWarnings("unchecked")
    @Test
    void 释放按owner原子判定() {
        when(redis.execute(any(RedisScript.class), anyList(), anyString()))
                .thenReturn(1L, 0L);

        // 持有者释放成功,非持有者释放无效;两种结果均不抛异常
        lockService.release(1L, 100L);
        lockService.release(1L, 200L);
    }
}
