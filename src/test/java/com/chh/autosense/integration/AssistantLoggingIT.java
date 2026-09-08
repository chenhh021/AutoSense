package com.chh.autosense.integration;

import com.chh.autosense.core.routing.CapabilityResult;
import com.chh.autosense.domain.enums.AssistantCapability;
import com.chh.autosense.support.LogCaptureSupport;
import com.chh.autosense.support.RecordingCapabilityConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T044:助手日志集成测试(真实 MySQL/Redis + Log4j2 捕获)——两会话并发与
 * 异步收尾回调不串 MDC;关键英文事件级别正确;prompt 正文/用户原文/凭据/
 * 逐 token 内容不进入日志;回滚路径无假成功记录。
 */
@Import(RecordingCapabilityConfiguration.class)
class AssistantLoggingIT extends AbstractIntegrationIT {

    private static final Pattern SESSION_ID_IN_MESSAGE = Pattern.compile("sessionId=(\\d+)");

    @Autowired
    private RecordingCapabilityConfiguration.Recorder recorder;

    private String token;

    @BeforeEach
    void authenticate() {
        recorder.reset();
        String account = "it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        restTemplate.postForEntity(url("/api/v1/users/register"), Map.of("userAccount", account,
                "userPassword", "testPass123", "confirmPassword", "testPass123"), JsonNode.class);
        ResponseEntity<JsonNode> login = restTemplate.postForEntity(url("/api/v1/users/login"),
                Map.of("userAccount", account, "userPassword", "testPass123"), JsonNode.class);
        token = login.getBody().get("data").get("token").asText();
    }

    @Test
    void 并发会话与异步回调的日志MDC互不串号() throws Exception {
        CompletableFuture<CapabilityResult> first = new CompletableFuture<>();
        CompletableFuture<CapabilityResult> second = new CompletableFuture<>();
        CountDownLatch entered = new CountDownLatch(2);
        recorder.behavior.put(AssistantCapability.KNOWLEDGE, request -> {
            entered.countDown();
            return request.content().contains("寿命") ? first : second;
        });

        String bodyA;
        String bodyB;
        try (ExecutorService executor = Executors.newFixedThreadPool(2);
             LogCaptureSupport logs = new LogCaptureSupport()) {
            Future<String> postA = executor.submit(() ->
                    post(Map.of("problem", "灯泡寿命多久")));
            Future<String> postB = executor.submit(() ->
                    post(Map.of("problem", "什么是智能灯泡")));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            // 交错收尾:先完成后发起的会话,验证回调线程上下文独立
            second.complete(CapabilityResult.answer("回答B"));
            first.complete(CapabilityResult.answer("回答A"));
            bodyA = postA.get(15, TimeUnit.SECONDS);
            bodyB = postB.get(15, TimeUnit.SECONDS);
            assertThat(bodyA).contains("event:conclusion");
            assertThat(bodyB).contains("event:conclusion");

            List<LogEvent> completions = logs.events().stream()
                    .filter(e -> e.getMessage().getFormattedMessage()
                            .contains("Session request completed"))
                    .toList();
            assertThat(completions).hasSizeGreaterThanOrEqualTo(2);
            for (LogEvent event : completions) {
                String message = event.getMessage().getFormattedMessage();
                Matcher matcher = SESSION_ID_IN_MESSAGE.matcher(message);
                assertThat(matcher.find()).isTrue();
                // 异步回调线程上的 MDC 必须与事件所属会话一致,不得串到另一会话
                String mdcSessionId = event.getContextData().getValue("sessionId");
                assertThat(mdcSessionId)
                        .as("MDC sessionId 应与事件消息一致: %s", message)
                        .isEqualTo(matcher.group(1));
                assertThat(event.getLevel()).isEqualTo(Level.INFO);
                assertThat(message).contains("result=SUCCESS");
            }

            String rendered = logs.rendered();
            assertThat(rendered)
                    .doesNotContain("灯泡寿命多久")      // 用户原文
                    .doesNotContain("什么是智能灯泡")
                    .doesNotContain("testPass123")       // 凭据
                    .doesNotContain("Bearer")            // 令牌
                    .doesNotContain("{{history}}")       // prompt 模板
                    .doesNotContain("test receiver");    // 逐 token 内容
        }
    }

    @Test
    void 业务失败记录FAILED而非假成功() {
        recorder.behavior.put(AssistantCapability.KNOWLEDGE, request ->
                CompletableFuture.completedFuture(CapabilityResult.failure(
                        com.chh.autosense.exception.ErrorCode.AI_SERVICE_UNAVAILABLE,
                        "助手服务暂时不可用,请稍后再试。")));

        try (LogCaptureSupport logs = new LogCaptureSupport()) {
            String body = post(Map.of("problem", "什么是智能灯泡"));
            assertThat(body).contains("event:error").contains("AI_SERVICE_UNAVAILABLE");

            List<LogEvent> completions = logs.events().stream()
                    .filter(e -> e.getMessage().getFormattedMessage()
                            .contains("Session request completed"))
                    .toList();
            assertThat(completions).hasSize(1);
            LogEvent failure = completions.get(0);
            assertThat(failure.getMessage().getFormattedMessage())
                    .contains("result=FAILED", "errorCode=AI_SERVICE_UNAVAILABLE")
                    .doesNotContain("result=SUCCESS");
        }
    }

    private String post(Object body) {
        ResponseEntity<String> result = restTemplate.exchange(url("/api/v1/sessions"),
                HttpMethod.POST, new HttpEntity<>(body, authHeaders(token)), String.class);
        assertThat(result.getStatusCode().value()).isEqualTo(200);
        return result.getBody();
    }
}
