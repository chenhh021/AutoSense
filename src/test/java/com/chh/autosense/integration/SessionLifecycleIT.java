package com.chh.autosense.integration;

import com.chh.autosense.core.routing.CapabilityResult;
import com.chh.autosense.domain.enums.AssistantCapability;
import com.chh.autosense.support.RecordingCapabilityConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T043:会话生命周期集成测试(真实 MySQL/Redis + SSE)——同会话并发忙请求不写消息、
 * 固定截止经 GET 补偿结清 REQUEST_TIMEOUT、迟到回调不污染已结清轮次、
 * 失租约后 token 停止但 DB 指针仍为准、恢复零模型重放/零设备写。
 */
@Import(RecordingCapabilityConfiguration.class)
class SessionLifecycleIT extends AbstractIntegrationIT {

    @Autowired
    private RecordingCapabilityConfiguration.Recorder recorder;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JdbcTemplate jdbc;

    private String token;

    @BeforeEach
    void authenticate() {
        recorder.reset();
        String account = "it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        restTemplate.postForEntity(url("/api/v1/users/register"), Map.of("userAccount", account,
                "userPassword", "testPass123", "confirmPassword", "testPass123"), JsonNode.class);
        ResponseEntity<JsonNode> login = restTemplate.postForEntity(url("/api/v1/users/login"),
                Map.of("userAccount", account, "userPassword", "testPass123"), JsonNode.class);
        token = login.getBody().get("token").asText();
    }

    @Test
    void 同会话并发忙请求拒绝且不写消息() throws Exception {
        CompletableFuture<CapabilityResult> blocking = new CompletableFuture<>();
        CountDownLatch entered = new CountDownLatch(1);
        recorder.behavior.put(AssistantCapability.KNOWLEDGE, request -> {
            entered.countDown();
            return blocking;
        });

        String sessionIdHolder;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<String> first = executor.submit(() -> post("", Map.of("problem", "什么是智能灯泡")));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            long sessionId = recorder.calls.get(0).sessionId();

            // 处理中再发一条:SESSION_BUSY,且不新增任何消息
            String busy = post("/" + sessionId + "/messages", Map.of("content", "催一下"));
            assertThat(busy).contains("event:error").contains("SESSION_BUSY");
            Long messageCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM chat_message WHERE session_id = ?", Long.class, sessionId);
            assertThat(messageCount).isEqualTo(1); // 仅首轮用户消息

            blocking.complete(CapabilityResult.answer("约五年"));
            sessionIdHolder = first.get(15, TimeUnit.SECONDS);
        }
        assertThat(sessionIdHolder).contains("event:conclusion");
    }

    @Test
    void 截止已过经GET补偿结清REQUEST_TIMEOUT且后续POST零模型重放() {
        CompletableFuture<CapabilityResult> blocking = new CompletableFuture<>();
        CountDownLatch entered = new CountDownLatch(1);
        recorder.behavior.put(AssistantCapability.KNOWLEDGE, request -> {
            entered.countDown();
            return blocking;
        });

        // 首个请求的旧 SSE 流不再被补偿写回(断线不回放);守护线程避免阻塞测试退出
        Thread asyncPost = new Thread(() -> post("", Map.of("problem", "什么是智能灯泡")));
        asyncPost.setDaemon(true);
        asyncPost.start();
        try {
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        long sessionId = recorder.calls.get(0).sessionId();

        // 将数据库截止时间拨到过去(等价于崩溃后恢复时截止已过)
        jdbc.update("UPDATE repair_session SET processing_deadline_at = DATE_SUB(CURRENT_TIMESTAMP,"
                + " INTERVAL 1 SECOND) WHERE id = ?", sessionId);

        // GET 补查触发补偿结清
        JsonNode session = getSession(sessionId);
        assertThat(session.get("status").asText()).isEqualTo("FAILED_REQUEST");
        assertThat(session.path("conclusion").path("type").asText()).isEqualTo("ERROR");

        // 补偿后迟到回调不得改写已结清轮次
        blocking.complete(CapabilityResult.answer("迟到的回答"));
        awaitQuietly();
        JsonNode afterLate = getSession(sessionId);
        assertThat(afterLate.get("status").asText()).isEqualTo("FAILED_REQUEST");
        Long assistantMessages = jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_message WHERE session_id = ? AND role = 'ASSISTANT'",
                Long.class, sessionId);
        assertThat(assistantMessages).isEqualTo(1); // 仅超时说明,迟到结果未落库

        // 后续 POST 开启新轮,不再重放旧轮(新一轮才新增一次能力调用)
        int callsBefore = recorder.calls.size();
        recorder.behavior.remove(AssistantCapability.KNOWLEDGE);
        String next = post("/" + sessionId + "/messages", Map.of("content", "灯泡寿命多久"));
        assertThat(next).contains("event:conclusion").doesNotContain("event:error");
        assertThat(recorder.calls).hasSize(callsBefore + 1);
        assertThat(recorder.calls.get(callsBefore).round()).isEqualTo(2);
    }

    @Test
    void 租约被抢占后结果仍按DB指针收尾() throws Exception {
        CompletableFuture<CapabilityResult> blocking = new CompletableFuture<>();
        CountDownLatch entered = new CountDownLatch(1);
        recorder.behavior.put(AssistantCapability.KNOWLEDGE, request -> {
            entered.countDown();
            return blocking;
        });

        Future<String> first;
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            first = executor.submit(() -> post("", Map.of("problem", "什么是智能灯泡")));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            long sessionId = recorder.calls.get(0).sessionId();

            // 抢占租约键:owner 不再匹配,后续续期失败并停止 token
            redis.opsForValue().set("autosense:lock:session:" + sessionId, "stolen",
                    java.time.Duration.ofSeconds(60));

            blocking.complete(CapabilityResult.answer("正常收尾"));
            String body = first.get(15, TimeUnit.SECONDS);
            assertThat(body).contains("event:conclusion");
        }

        // DB 指针仍为准:结论落库,处理指针清空
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, processing_message_id FROM repair_session WHERE id = ?",
                recorder.calls.get(0).sessionId());
        assertThat(row.get("status")).isEqualTo("COMPLETED_ANSWERED");
        assertThat(row.get("processing_message_id")).isNull();
    }

    @Test
    void 会话忙期间GET补查返回当前处理状态而不结清() throws Exception {
        CompletableFuture<CapabilityResult> blocking = new CompletableFuture<>();
        CountDownLatch entered = new CountDownLatch(1);
        recorder.behavior.put(AssistantCapability.KNOWLEDGE, request -> {
            entered.countDown();
            return blocking;
        });

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<String> first = executor.submit(() ->
                    post("", Map.of("problem", "什么是智能灯泡")));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            long sessionId = recorder.calls.get(0).sessionId();

            // 截止未到:GET 不结清,仅返回进行中状态
            JsonNode session = getSession(sessionId);
            assertThat(session.get("status").asText()).isEqualTo("DISPATCHING");
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT processing_message_id FROM repair_session WHERE id = ?", sessionId);
            assertThat(row.get("processing_message_id")).isNotNull();

            blocking.complete(CapabilityResult.answer("约五年"));
            assertThat(first.get(15, TimeUnit.SECONDS)).contains("event:conclusion");
        }
    }

    // ---------- 辅助 ----------

    private String post(String suffix, Object body) {
        ResponseEntity<String> result = restTemplate.exchange(url("/api/v1/sessions" + suffix),
                HttpMethod.POST, new HttpEntity<>(body, authHeaders(token)), String.class);
        assertThat(result.getStatusCode().value()).isEqualTo(200);
        return result.getBody();
    }

    private JsonNode getSession(long sessionId) {
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                url("/api/v1/sessions/" + sessionId), HttpMethod.GET,
                new HttpEntity<>(authHeaders(token)), JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return response.getBody();
    }

    private void awaitQuietly() {
        try {
            Thread.sleep(800);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
