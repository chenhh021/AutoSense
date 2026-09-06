package com.chh.autosense.service.user;

import com.chh.autosense.config.AuthProperties;
import com.chh.autosense.core.security.AuthUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

/**
 * 登录令牌服务(R19,FR-023/025/027):不透明随机令牌存 Redis。
 * 键 `autosense:token:{token}` → JSON {userId, role},TTL 滚动续期;
 * 辅助索引 `autosense:usertokens:{userId}`(Set)支撑禁用即全端踢下线。
 */
@Service
public class AuthTokenService {

    public static final String TOKEN_PREFIX = "autosense:token:";
    public static final String USER_TOKENS_PREFIX = "autosense:usertokens:";

    private static final SecureRandom RANDOM = new SecureRandom();

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

    /** 登录颁发:32 字节 SecureRandom,Base64URL(无填充)不透明令牌。 */
    public String issue(Long userId, String role) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        try {
            redis.opsForValue().set(TOKEN_PREFIX + token,
                    objectMapper.writeValueAsString(Map.of("userId", userId, "role", role)), ttl());
        } catch (Exception e) {
            throw new IllegalStateException("令牌写入失败", e);
        }
        redis.opsForSet().add(USER_TOKENS_PREFIX + userId, token);
        redis.expire(USER_TOKENS_PREFIX + userId, ttl());
        return token;
    }

    /** 解析并滚动续期;令牌不存在/非法返回 null。 */
    public AuthUser resolve(String token) {
        String value = redis.opsForValue().get(TOKEN_PREFIX + token);
        if (value == null) {
            return null;
        }
        redis.expire(TOKEN_PREFIX + token, ttl());
        try {
            JsonNode node = objectMapper.readTree(value);
            return new AuthUser(node.get("userId").asLong(), node.get("role").asText());
        } catch (Exception e) {
            return null;
        }
    }

    /** 注销:删除单个令牌,立即失效(FR-025)。 */
    public void invalidate(String token) {
        AuthUser user = resolve(token);
        redis.delete(TOKEN_PREFIX + token);
        if (user != null) {
            redis.opsForSet().remove(USER_TOKENS_PREFIX + user.userId(), token);
        }
    }

    /** 禁用用户:删除其全部令牌,立即全端失效(FR-027)。 */
    public void invalidateAll(Long userId) {
        Set<String> tokens = redis.opsForSet().members(USER_TOKENS_PREFIX + userId);
        if (tokens != null) {
            tokens.forEach(t -> redis.delete(TOKEN_PREFIX + t));
        }
        redis.delete(USER_TOKENS_PREFIX + userId);
    }
}
