package com.chh.autosense.integration;

import com.chh.autosense.core.session.DeviceLockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T032:设备互斥锁集成测试(真实 Redis)——原子 owner 校验:
 * 错误 owner 续租/释放失败且锁保持;到期后原 owner 续租失败、他人可重新加锁;
 * 续租将 TTL 重置为 10 分钟。原子性以真实 Redis 行为断言,不用 Mockito 调用次数代替。
 */
class DeviceLockIT extends AbstractIntegrationIT {

    private static final long DEVICE_ID = 900001L;

    @Autowired
    private DeviceLockService lockService;

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void clean() {
        Set<String> keys = redis.keys(DeviceLockService.KEY_PREFIX + DEVICE_ID + "*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    void 仅持有者可续租与释放() {
        assertThat(lockService.tryLock(DEVICE_ID, 100L)).isTrue();

        // 错误 owner:续租失败、释放无效,锁仍归原 owner
        assertThat(lockService.renew(DEVICE_ID, 200L)).isFalse();
        lockService.release(DEVICE_ID, 200L);
        assertThat(redis.hasKey(DeviceLockService.KEY_PREFIX + DEVICE_ID)).isTrue();

        // 原 owner:续租成功且 TTL 重置回约 10 分钟
        redis.expire(DeviceLockService.KEY_PREFIX + DEVICE_ID, Duration.ofSeconds(30));
        assertThat(lockService.renew(DEVICE_ID, 100L)).isTrue();
        assertThat(redis.getExpire(DeviceLockService.KEY_PREFIX + DEVICE_ID))
                .isGreaterThan(9L * 60 - 5);

        // 原 owner 释放后他人可加锁
        lockService.release(DEVICE_ID, 100L);
        assertThat(redis.hasKey(DeviceLockService.KEY_PREFIX + DEVICE_ID)).isFalse();
        assertThat(lockService.tryLock(DEVICE_ID, 200L)).isTrue();
    }

    @Test
    void 到期竞争_原owner续租失败他人可加锁() throws Exception {
        assertThat(lockService.tryLock(DEVICE_ID, 100L)).isTrue();
        // 人为压缩 TTL 模拟锁到期
        redis.expire(DeviceLockService.KEY_PREFIX + DEVICE_ID, Duration.ofMillis(80));
        Thread.sleep(200);

        assertThat(lockService.renew(DEVICE_ID, 100L)).isFalse();
        lockService.release(DEVICE_ID, 100L);
        assertThat(lockService.tryLock(DEVICE_ID, 200L)).isTrue();
    }

    @Test
    void 已持有设备再加锁互斥() {
        assertThat(lockService.tryLock(DEVICE_ID, 100L)).isTrue();
        assertThat(lockService.tryLock(DEVICE_ID, 101L)).isFalse();
    }
}
