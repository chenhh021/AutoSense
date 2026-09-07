package com.chh.autosense.service.user;

import com.chh.autosense.config.AuthProperties;
import com.chh.autosense.core.security.AuthUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 登录令牌服务（user-device-api §2，data-model §6/§7）：不透明随机令牌存 Redis。
 * 键 autosense:token:{token} → JSON {userId, role}；索引 autosense:usertokens:{userId}。
 * 令牌本体与索引成员必须同时有效；签发/校验续期/注销均以原子脚本维护两键并统一滚动 TTL。
 * 缺索引成员即无效（孤立 token 不补回）；索引撤销失败抛错，残留本体仅尽力清理。
 * 日志与异常不携带完整键或令牌内容。
 */
@Service
@Slf4j
public class AuthTokenService {

    public static final String TOKEN_PREFIX = "autosense:token:";
    public static final String USER_TOKENS_PREFIX = "autosense:usertokens:";

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final DefaultRedisScript<Long> ISSUE = new DefaultRedisScript<>(
            "redis.call('set', KEYS[1], ARGV[1], 'px', ARGV[2]) "
                    + "redis.call('sadd', KEYS[2], ARGV[3]) "
                    + "redis.call('pexpire', KEYS[2], ARGV[2]) return 1", Long.class);

    private static final DefaultRedisScript<Long> CHECK_AND_RENEW = new DefaultRedisScript<>(
            "if redis.call('sismember', KEYS[2], ARGV[1]) == 1 then "
                    + "redis.call('pexpire', KEYS[1], ARGV[2]) "
                    + "redis.call('pexpire', KEYS[2], ARGV[2]) return 1 else return 0 end", Long.class);

    private static final DefaultRedisScript<Long> REVOKE = new DefaultRedisScript<>(
            "redis.call('del', KEYS[1]) return redis.call('srem', KEYS[2], ARGV[1])", Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final AuthProperties properties;

    public AuthTokenService(StringRedisTemplate redis, ObjectMapper objectMapper,
                            AuthProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    private Duration ttl() {
        return Duration.ofDays(properties.tokenTtlDays());
    }

    /** 登录颁发：32 字节 SecureRandom Base64URL 令牌；token 与索引原子写入并统一 TTL。 */
    public String issue(Long userId, String role) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String payload;
        try {
            payload = objectMapper.writeValueAsString(Map.of("userId", userId, "role", role));
        } catch (Exception e) {
            throw new IllegalStateException("Token payload encoding failed");
        }
        redis.execute(ISSUE, List.of(TOKEN_PREFIX + token, USER_TOKENS_PREFIX + userId),
                payload, Long.toString(ttl().toMillis()), token);
        return token;
    }

    /**
     * 解析并滚动续期：token 本体存在且是所属用户索引成员才有效，两键统一续期。
     * 缺索引成员的孤立 token 直接无效，不补回。
     *
     * @return 有效返回 AuthUser；缺失/非法/被撤销返回 null
     */
    public AuthUser resolve(String token) {
        String value = redis.opsForValue().get(TOKEN_PREFIX + token);
        if (value == null) {
            return null;
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(value);
        } catch (Exception e) {
            return null;
        }
        long userId = node.get("userId").asLong();
        String role = node.get("role").asText();
        Long valid = redis.execute(CHECK_AND_RENEW,
                List.of(TOKEN_PREFIX + token, USER_TOKENS_PREFIX + userId),
                token, Long.toString(ttl().toMillis()));
        if (!Long.valueOf(1).equals(valid)) {
            return null;
        }
        return new AuthUser(userId, role);
    }

    /** 注销：原子删除单个令牌及其索引成员，立即失效。 */
    public void invalidate(String token) {
        String value = redis.opsForValue().get(TOKEN_PREFIX + token);
        if (value == null) {
            return;
        }
        try {
            long userId = objectMapper.readTree(value).get("userId").asLong();
            redis.execute(REVOKE, List.of(TOKEN_PREFIX + token, USER_TOKENS_PREFIX + userId), token);
        } catch (Exception e) {
            redis.delete(TOKEN_PREFIX + token);
        }
    }

    /**
     * 撤销用户全部令牌：先删索引（撤销立即生效，失败抛错由调用方回滚），
     * 已撤销后的残留 token 本体仅尽力清理，不恢复旧成员。
     */
    public void invalidateAll(Long userId) {
        Set<String> tokens = redis.opsForSet().members(USER_TOKENS_PREFIX + userId);
        redis.delete(USER_TOKENS_PREFIX + userId);
        if (tokens != null) {
            tokens.forEach(token -> {
                try {
                    redis.delete(TOKEN_PREFIX + token);
                } catch (Exception e) {
                    log.warn("User operation rejected: operation=token-cleanup, reasonCode=ORPHAN_TOKEN_LEFT");
                }
            });
        }
    }
}
