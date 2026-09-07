package com.chh.autosense.core.session;

import com.chh.autosense.config.AssistantProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Service
public class SessionLeaseService {
    private static final DefaultRedisScript<Long> RENEW = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('pexpire', KEYS[1], ARGV[2]) else return 0 end", Long.class);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end", Long.class);
    private final StringRedisTemplate redis;
    private final Duration ttl;

    public SessionLeaseService(StringRedisTemplate redis, AssistantProperties properties) {
        this.redis = redis;
        this.ttl = Duration.ofSeconds(properties.sessionLeaseSeconds());
    }

    /** Owner stays private and is never a logging or authorization value. */
    public static final class Lease {
        private final long sessionId;
        private final String owner;
        private Lease(long sessionId, long messageId) {
            this.sessionId = sessionId;
            this.owner = messageId + ":" + UUID.randomUUID();
        }
    }

    public Lease acquire(long sessionId, long messageId) {
        Lease lease = new Lease(sessionId, messageId);
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key(sessionId), lease.owner, ttl)) ? lease : null;
    }

    public boolean renew(Lease lease) {
        return Long.valueOf(1).equals(redis.execute(RENEW, List.of(key(lease.sessionId)), lease.owner,
                Long.toString(ttl.toMillis())));
    }

    public boolean release(Lease lease) {
        return Long.valueOf(1).equals(redis.execute(RELEASE, List.of(key(lease.sessionId)), lease.owner));
    }

    private String key(long sessionId) { return "autosense:lock:session:" + sessionId; }
}
