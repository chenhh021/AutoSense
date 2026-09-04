package com.chh.autosense.unit;

import com.chh.autosense.session.DeviceLockService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Test
    void 仅持有者可释放锁() {
        when(valueOps.get("autosense:lock:device:1")).thenReturn("100");
        lockService.release(1L, 100L);
        verify(redis).delete("autosense:lock:device:1");

        when(valueOps.get("autosense:lock:device:3")).thenReturn("200");
        lockService.release(3L, 100L);
        verify(redis, never()).delete("autosense:lock:device:3");
    }
}
