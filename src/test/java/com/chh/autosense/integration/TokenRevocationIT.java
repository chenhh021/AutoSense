package com.chh.autosense.integration;

import com.chh.autosense.service.user.AuthTokenService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T031:令牌撤销集成测试(真实 MySQL/Redis)——token 与 usertokens 索引共同有效、
 * 统一滚动 TTL、孤立 token 无效且不补回、注销、禁用再启用旧令牌不复活、并发签发
 * 全部失效、Redis 关键失败传播并回滚数据库(启用失败保持禁用)。
 * 全程使用真实登录令牌,不以 dev token 证明撤销;故障注入经 @MockitoSpyBean
 * 限定于本测试类的独立 Spring 上下文。
 */
class TokenRevocationIT extends AbstractIntegrationIT {

    @Autowired
    private StringRedisTemplate redis;

    @MockitoSpyBean
    private AuthTokenService tokenService;

    // ---------- token 与索引共同有效 / 统一 TTL ----------

    @Test
    void 令牌与索引键同时存在且TTL统一滚动() throws Exception {
        String account = register("tr_ttl");
        String token = login(account, "pass1234");
        Long userId = meId(token);

        String tokenKey = AuthTokenService.TOKEN_PREFIX + token;
        String indexKey = AuthTokenService.USER_TOKENS_PREFIX + userId;
        assertThat(redis.hasKey(tokenKey)).isTrue();
        assertThat(redis.opsForSet().isMember(indexKey, token)).isTrue();

        // 两键 TTL 均为 7 天滚动窗口内
        Long tokenTtlSeconds = redis.getExpire(tokenKey);
        Long indexTtlSeconds = redis.getExpire(indexKey);
        assertThat(tokenTtlSeconds).isBetween(6L * 24 * 3600, 7L * 24 * 3600);
        assertThat(indexTtlSeconds).isBetween(6L * 24 * 3600, 7L * 24 * 3600);

        // 访问 me 触发滚动续期:先压低 TTL,再访问,两键 TTL 被统一抬回
        redis.expire(tokenKey, Duration.ofMinutes(5));
        redis.expire(indexKey, Duration.ofMinutes(5));
        assertThat(meStatus(token)).isEqualTo(200);
        assertThat(redis.getExpire(tokenKey)).isGreaterThan(6L * 24 * 3600);
        assertThat(redis.getExpire(indexKey)).isGreaterThan(6L * 24 * 3600);
    }

    // ---------- 孤立 token 无效且不补回 ----------

    @Test
    void 缺索引成员的孤立token无效且不补回() throws Exception {
        String account = register("tr_orphan");
        String token = login(account, "pass1234");
        Long userId = meId(token);
        String indexKey = AuthTokenService.USER_TOKENS_PREFIX + userId;

        // 仅删索引成员,保留 token 本体 → 孤立 token
        redis.opsForSet().remove(indexKey, token);
        assertThat(redis.hasKey(AuthTokenService.TOKEN_PREFIX + token)).isTrue();

        assertThat(meStatus(token)).isEqualTo(401);
        // 访问后不得补回索引成员
        assertThat(redis.opsForSet().isMember(indexKey, token)).isNotEqualTo(true);
    }

    // ---------- 注销立即失效 ----------

    @Test
    void 注销同时删除token与索引成员() throws Exception {
        String account = register("tr_logout");
        String token = login(account, "pass1234");
        Long userId = meId(token);

        ResponseEntity<String> logout = restTemplate.exchange(url("/api/v1/users/logout"),
                HttpMethod.POST, new HttpEntity<>(auth(token)), String.class);
        assertThat(logout.getStatusCode().value()).isEqualTo(204);

        assertThat(redis.hasKey(AuthTokenService.TOKEN_PREFIX + token)).isFalse();
        assertThat(redis.opsForSet()
                .isMember(AuthTokenService.USER_TOKENS_PREFIX + userId, token)).isNotEqualTo(true);
        assertThat(meStatus(token)).isEqualTo(401);
    }

    // ---------- 禁用再启用旧令牌不复活 ----------

    @Test
    void 禁用再启用旧令牌不复活() throws Exception {
        String account = register("tr_revive");
        String oldToken = login(account, "pass1234");
        long userId = meId(oldToken);
        String adminToken = login("admin", "admin123");

        setStatus(adminToken, userId, true, 200);
        assertThat(meStatus(oldToken)).isEqualTo(401);

        setStatus(adminToken, userId, false, 200);
        // 启用后旧令牌仍然无效,索引不恢复旧成员
        assertThat(meStatus(oldToken)).isEqualTo(401);
        assertThat(redis.opsForSet()
                .isMember(AuthTokenService.USER_TOKENS_PREFIX + userId, oldToken))
                .isNotEqualTo(true);
        // 新登录可用且与旧令牌无关
        String newToken = login(account, "pass1234");
        assertThat(newToken).isNotEqualTo(oldToken);
        assertThat(meStatus(newToken)).isEqualTo(200);
    }

    // ---------- 并发签发与统一失效 ----------

    @Test
    void 并发签发全部有效且禁用后全部失效() throws Exception {
        String account = register("tr_conc");
        login(account, "pass1234"); // 确保账号已建立

        int workers = 6;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(workers)) {
            for (int i = 0; i < workers; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return login(account, "pass1234");
                }));
            }
            ready.await();
            start.countDown();
            List<String> tokens = new ArrayList<>();
            for (Future<String> future : futures) {
                tokens.add(future.get());
            }
            assertThat(tokens).doesNotContainNull().doesNotHaveDuplicates();
            for (String token : tokens) {
                assertThat(meStatus(token)).isEqualTo(200);
            }

            long userId = meId(tokens.get(0));
            String adminToken = login("admin", "admin123");
            setStatus(adminToken, userId, true, 200);
            for (String token : tokens) {
                assertThat(meStatus(token)).isEqualTo(401);
            }
        }
    }

    // ---------- Redis 关键失败回滚 ----------

    @Test
    void 启用时撤销索引失败则回滚且保持禁用() throws Exception {
        String account = register("tr_rb_enable");
        String token = login(account, "pass1234");
        long userId = meId(token);
        String adminToken = login("admin", "admin123");
        setStatus(adminToken, userId, true, 200);

        Mockito.doThrow(new DataAccessResourceFailureException("injected redis down"))
                .when(tokenService).invalidateAll(userId);
        try {
            setStatus(adminToken, userId, false, 500);
        } finally {
            Mockito.reset(tokenService);
        }
        // 回滚:仍为禁用,登录 401
        assertThat(post("/api/v1/users/login", Map.of(
                "userAccount", account, "userPassword", "pass1234"))
                .getStatusCode().value()).isEqualTo(401);

        // 故障解除后启用成功
        setStatus(adminToken, userId, false, 200);
        assertThat(login(account, "pass1234")).isNotBlank();
    }

    @Test
    void 禁用时撤销索引失败则回滚且保持启用() throws Exception {
        String account = register("tr_rb_disable");
        String token = login(account, "pass1234");
        long userId = meId(token);
        String adminToken = login("admin", "admin123");

        Mockito.doThrow(new DataAccessResourceFailureException("injected redis down"))
                .when(tokenService).invalidateAll(userId);
        try {
            setStatus(adminToken, userId, true, 500);
        } finally {
            Mockito.reset(tokenService);
        }
        // 回滚:未禁用,原令牌仍可用
        assertThat(meStatus(token)).isEqualTo(200);
    }

    // ---------- 辅助 ----------

    private String register(String prefix) {
        String account = prefix + "_" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 8);
        ResponseEntity<JsonNode> reg = post("/api/v1/users/register", Map.of(
                "userAccount", account, "userPassword", "pass1234",
                "confirmPassword", "pass1234"));
        assertThat(reg.getStatusCode().value()).as("注册: %s", reg.getBody()).isEqualTo(201);
        return account;
    }

    private String login(String account, String password) {
        ResponseEntity<JsonNode> resp = post("/api/v1/users/login", Map.of(
                "userAccount", account, "userPassword", password));
        assertThat(resp.getStatusCode().value()).as("登录 %s: %s", account, resp.getBody())
                .isEqualTo(200);
        return resp.getBody().get("token").asText();
    }

    private Long meId(String token) {
        ResponseEntity<JsonNode> me = restTemplate.exchange(url("/api/v1/users/me"),
                HttpMethod.GET, new HttpEntity<>(auth(token)), JsonNode.class);
        assertThat(me.getStatusCode().value()).as("me: %s", me.getBody()).isEqualTo(200);
        return me.getBody().get("id").asLong();
    }

    private int meStatus(String token) {
        return restTemplate.exchange(url("/api/v1/users/me"), HttpMethod.GET,
                new HttpEntity<>(auth(token)), String.class).getStatusCode().value();
    }

    private void setStatus(String adminToken, long userId, boolean disabled, int expected) {
        ResponseEntity<JsonNode> resp = restTemplate.exchange(
                url("/api/v1/admin/users/" + userId + "/status"), HttpMethod.PUT,
                new HttpEntity<>(Map.of("disabled", disabled), auth(adminToken)), JsonNode.class);
        assertThat(resp.getStatusCode().value()).as("setStatus: %s", resp.getBody())
                .isEqualTo(expected);
    }

    private HttpHeaders auth(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseEntity<JsonNode> post(String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(url(path), HttpMethod.POST,
                new HttpEntity<>(body, headers), JsonNode.class);
    }
}
