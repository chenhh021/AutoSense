package com.chh.autosense.integration;

import com.chh.autosense.core.routing.CapabilityResult;
import com.chh.autosense.domain.dto.ConclusionDto;
import com.chh.autosense.domain.enums.AssistantCapability;
import com.chh.autosense.core.aftersales.AfterSalesLocation;
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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T042:对话历史集成测试(真实 MySQL/Redis,公共入口)——跨能力/多轮历史按序完整、
 * 当前输入只保存一次、相同文本不去重、含字面模板标记/反斜杠的原文原样保留、
 * 内部 prompt 包装不进入历史、多用户隔离、旧 Redis 键不干扰、
 * 完整结论字段(人工步骤/售后)保留且处理指针清空、旧上下文失效明确报错。
 * 记录能力仅构造结论,不读不改设备。
 */
@Import(RecordingCapabilityConfiguration.class)
class ConversationHistoryIT extends AbstractIntegrationIT {

    @Autowired
    private RecordingCapabilityConfiguration.Recorder recorder;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JdbcTemplate jdbc;

    private String token;
    private long userId;

    @BeforeEach
    void authenticate() {
        recorder.reset();
        String account = "it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        ResponseEntity<JsonNode> registration = restTemplate.postForEntity(
                url("/api/v1/users/register"), Map.of("userAccount", account,
                        "userPassword", "testPass123", "confirmPassword", "testPass123"),
                JsonNode.class);
        assertThat(registration.getStatusCode().value()).isEqualTo(201);
        userId = registration.getBody().get("id").asLong();
        ResponseEntity<JsonNode> login = restTemplate.postForEntity(url("/api/v1/users/login"),
                Map.of("userAccount", account, "userPassword", "testPass123"), JsonNode.class);
        token = login.getBody().get("token").asText();
    }

    @Test
    void 多轮跨能力历史按序完整且当前输入只保存一次() {
        String first = post("", Map.of("problem", "什么是智能灯泡"));
        long sessionId = sessionIdOf(first);
        String second = post("/" + sessionId + "/messages", Map.of("content", "列出我的所有设备"));
        assertThat(second).contains("event:conclusion");

        JsonNode history = messages(sessionId);
        assertThat(history).hasSize(4);
        assertThat(history.get(0).get("role").asText()).isEqualTo("USER");
        assertThat(history.get(0).get("content").asText()).isEqualTo("什么是智能灯泡");
        assertThat(history.get(1).get("role").asText()).isEqualTo("ASSISTANT");
        assertThat(history.get(2).get("content").asText()).isEqualTo("列出我的所有设备");
        // 相同内容只保存一次:第二条用户消息与首轮不重复,当前输入仅一行
        Long copies = jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_message WHERE session_id = ? AND content = ?",
                Long.class, sessionId, "列出我的所有设备");
        assertThat(copies).isEqualTo(1);
        // 第二轮分类拿到的历史恰为上一轮一问一答
        assertThat(recorder.calls.get(1).history().messages()).hasSize(2);
        assertThat(recorder.calls.get(1).history().messages().get(0).content())
                .isEqualTo("什么是智能灯泡");
    }

    @Test
    void 字面模板标记与反斜杠原文保留且prompt包装不入库() {
        String literal = "灯 {{history}} 坏了,日志在 C:\\tmp\\灯.log";
        String first = post("", Map.of("problem", literal));
        long sessionId = sessionIdOf(first);

        JsonNode history = messages(sessionId);
        assertThat(history.get(0).get("content").asText()).isEqualTo(literal);
        // 第二轮历史携带原文;任何消息不得含渲染包装结构
        post("/" + sessionId + "/messages", Map.of("content", "列出我的所有设备"));
        assertThat(recorder.calls).hasSize(2);
        assertThat(recorder.calls.get(1).history().messages().get(0).content()).isEqualTo(literal);
        List<String> stored = jdbc.queryForList(
                "SELECT content FROM chat_message WHERE session_id = ?", String.class, sessionId);
        assertThat(stored).noneMatch(c -> c.contains("\"history\"") && c.contains("\"text\""));
    }

    @Test
    void 相邻轮相同文本不去重() {
        String first = post("", Map.of("problem", "什么是智能灯泡"));
        long sessionId = sessionIdOf(first);
        post("/" + sessionId + "/messages", Map.of("content", "什么是智能灯泡"));

        Long sameText = jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_message WHERE session_id = ? AND role = 'USER'"
                        + " AND content = ?",
                Long.class, sessionId, "什么是智能灯泡");
        assertThat(sameText).isEqualTo(2);
    }

    @Test
    void 他人会话历史不可见() {
        String first = post("", Map.of("problem", "什么是智能灯泡"));
        long sessionId = sessionIdOf(first);

        String other = "it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        restTemplate.postForEntity(url("/api/v1/users/register"), Map.of("userAccount", other,
                "userPassword", "testPass123", "confirmPassword", "testPass123"), JsonNode.class);
        ResponseEntity<JsonNode> login = restTemplate.postForEntity(url("/api/v1/users/login"),
                Map.of("userAccount", other, "userPassword", "testPass123"), JsonNode.class);
        String otherToken = login.getBody().get("token").asText();

        ResponseEntity<JsonNode> denied = restTemplate.exchange(
                url("/api/v1/sessions/" + sessionId + "/messages"), HttpMethod.GET,
                new HttpEntity<>(authHeaders(otherToken)), JsonNode.class);
        assertThat(denied.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void 旧Redis键不干扰历史与续聊() {
        String first = post("", Map.of("problem", "什么是智能灯泡"));
        long sessionId = sessionIdOf(first);
        // 旧格式缓存:v1 Hash 会话上下文与旧记忆键,内容均为脏数据
        redis.opsForHash().put("autosense:session:" + sessionId, "state", "stale");
        redis.opsForValue().set("autosense:memory:" + sessionId, "stale", Duration.ofMinutes(5));

        String second = post("/" + sessionId + "/messages", Map.of("content", "列出我的所有设备"));
        assertThat(second).contains("event:conclusion").doesNotContain("event:error");
        assertThat(recorder.calls.get(1).capability()).isEqualTo(AssistantCapability.DEVICE_QUERY);
        assertThat(recorder.calls.get(1).history().messages()).hasSize(2);
        assertThat(recorder.calls.get(1).history().messages().get(0).content())
                .isEqualTo("什么是智能灯泡");
    }

    @Test
    void 完整结论字段保留且处理指针清空() {
        recorder.behavior.put(AssistantCapability.KNOWLEDGE, request ->
                CompletableFuture.completedFuture(new CapabilityResult(
                        CapabilityResult.Kind.COMPLETE, "请按以下步骤自行处理",
                        com.chh.autosense.domain.enums.SessionStatus.COMPLETED_ANSWERED,
                        new ConclusionDto("UNFIXED_MANUAL_GUIDE", "请按以下步骤自行处理", null, null,
                                List.of("断电", "检查灯座"),
                                List.of(new AfterSalesLocation("示例网点", "示例地址", "10086", 1200L))),
                        null)));

        String first = post("", Map.of("problem", "什么是智能灯泡"));
        long sessionId = sessionIdOf(first);

        ResponseEntity<JsonNode> session = restTemplate.exchange(
                url("/api/v1/sessions/" + sessionId), HttpMethod.GET,
                new HttpEntity<>(authHeaders(token)), JsonNode.class);
        JsonNode conclusion = session.getBody().path("conclusion");
        assertThat(conclusion.path("manualSteps")).isNotNull();
        assertThat(conclusion.path("manualSteps").get(0).asText()).isEqualTo("断电");
        assertThat(conclusion.path("afterSales").get(0).path("name").asText()).isEqualTo("示例网点");
        // 投影与指针已同事务清空
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, processing_message_id FROM repair_session WHERE id = ?", sessionId);
        assertThat(row.get("processing_message_id")).isNull();
        assertThat(row.get("status")).isEqualTo("COMPLETED_ANSWERED");
    }

    @Test
    void 等待后上下文失效则明确报错且不派发() {
        recorder.behavior.put(AssistantCapability.KNOWLEDGE, request ->
                CompletableFuture.completedFuture(CapabilityResult.waiting(
                        "请提供所在位置", com.chh.autosense.domain.enums.SessionStatus.AWAITING_LOCATION)));
        String first = post("", Map.of("problem", "什么是智能灯泡"));
        long sessionId = sessionIdOf(first);
        assertThat(first).contains("event:awaiting");
        int callsBefore = recorder.calls.size();

        // 删除 v2 上下文(等价于旧 Hash/错版本:按上下文失效处理)
        redis.delete("autosense:session:v2:" + sessionId);

        String second = post("/" + sessionId + "/messages", Map.of("content", "在上海"));
        assertThat(second).contains("event:error").contains("CONTEXT_EXPIRED");
        assertThat(recorder.calls).hasSize(callsBefore);
    }

    // ---------- 辅助 ----------

    private String post(String suffix, Object body) {
        ResponseEntity<String> result = restTemplate.exchange(url("/api/v1/sessions" + suffix),
                HttpMethod.POST, new HttpEntity<>(body, authHeaders(token)), String.class);
        assertThat(result.getStatusCode().value()).isEqualTo(200);
        return result.getBody();
    }

    private JsonNode messages(long sessionId) {
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                url("/api/v1/sessions/" + sessionId + "/messages"), HttpMethod.GET,
                new HttpEntity<>(authHeaders(token)), JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return response.getBody();
    }

    private long sessionIdOf(String sseBody) {
        var match = Pattern.compile("\"sessionId\":(\\d+)").matcher(sseBody);
        assertThat(match.find()).isTrue();
        return Long.parseLong(match.group(1));
    }
}
