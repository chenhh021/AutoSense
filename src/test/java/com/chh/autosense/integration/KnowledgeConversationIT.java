package com.chh.autosense.integration;

import com.chh.autosense.core.device.client.DeviceServiceClient;
import com.chh.autosense.core.security.AuthUser;
import com.chh.autosense.service.knowledge.UserAiServiceCache;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.util.*;
import java.util.regex.Pattern;
import java.util.concurrent.*;
import com.chh.autosense.ai.rag.KnowledgeEmbeddingStore;
import com.chh.autosense.config.KnowledgeProperties;
import com.chh.autosense.support.*;
import dev.langchain4j.model.chat.*;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.data.message.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real HTTP/SSE, MySQL and Redis with actual cached AiServices and local models. */
@Import(KnowledgeConversationIT.KnowledgeModels.class)
@org.springframework.test.context.TestPropertySource(properties = "autosense.graph.mode=real")
class KnowledgeConversationIT extends AbstractIntegrationIT {
    @Autowired UserAiServiceCache cache;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @MockitoSpyBean DeviceServiceClient devices;
    private String token;
    private long userId;
    @Autowired DeterministicEmbeddingModel embedding;
    @Autowired ModelProbe probe;

    static class ModelProbe {
        final List<ChatRequest> requests = new CopyOnWriteArrayList<>();
        volatile CountDownLatch entered;
        volatile CountDownLatch release;
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class KnowledgeModels {
        @Bean ModelProbe modelProbe() { return new ModelProbe(); }
        @Bean @Primary DeterministicEmbeddingModel deterministicEmbedding() { return new DeterministicEmbeddingModel(); }
        @Bean(name = "knowledgeStoreConfiguration") KnowledgeEmbeddingStore knowledgeImport(KnowledgeProperties properties,
                DeterministicEmbeddingModel model) throws Exception {
            return new KnowledgeEmbeddingStore(properties, model, KnowledgeFixtures.resolver(KnowledgeFixtures.multipleProducts()));
        }
        @Bean @Primary ChatModel capturedKnowledgeChat(ModelProbe probe) {
            var delegate = new com.chh.autosense.ai.factory.MockKnowledgeAiServicesFactory().knowledgeMockChatModel();
            return new ChatModel() {
                @Override public ChatResponse doChat(ChatRequest request) {
                    probe.requests.add(request);
                    String body = ((UserMessage) request.messages().getLast()).singleText();
                    if (body.contains("\"evidence\"") && probe.entered != null) {
                        probe.entered.countDown();
                        try { if (!probe.release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test release timed out"); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                    }
                    return delegate.chat(request);
                }
            };
        }
    }

    @BeforeEach void authenticate() {
        clearInvocations(devices);
        probe.requests.clear(); probe.entered = null; probe.release = null;
        String account = "kn_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        var registration = restTemplate.postForEntity(url("/api/v1/users/register"), Map.of("userAccount", account,
                "userPassword", "testPass123", "confirmPassword", "testPass123"), JsonNode.class);
        assertThat(registration.getStatusCode().value()).isEqualTo(201);
        userId = registration.getBody().path("data").path("id").asLong();
        var login = restTemplate.postForEntity(url("/api/v1/users/login"), Map.of("userAccount", account,
                "userPassword", "testPass123"), JsonNode.class);
        token = login.getBody().path("data").path("token").asText();
    }

    @Test void concurrentTypesShareUserProxiesButPersistOnlyTheirOwnEvidence() throws Exception {
        var bundle = cache.getOrCreate(new AuthUser(userId));
        int startupCalls = embedding.indexingCalls.get();
        int queries = embedding.queryCalls.get();
        probe.entered = new CountDownLatch(2); probe.release = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var light = workers.submit(() -> post("", Map.of("problem", "light 型号使用参数")));
            var air = workers.submit(() -> post("", Map.of("problem", "air 型号使用参数")));
            assertThat(probe.entered.await(10, TimeUnit.SECONDS)).isTrue(); probe.release.countDown();
            String lightReply = light.get(10, TimeUnit.SECONDS), airReply = air.get(10, TimeUnit.SECONDS);
            assertThat(lightReply).contains("document/light/", "没有当前型号设备信息").doesNotContain("document/air/");
            assertThat(airReply).contains("document/air/", "ACME-A1").doesNotContain("document/light/");
            assertPersisted(sessionId(lightReply), lightReply, 1); assertPersisted(sessionId(airReply), airReply, 1);
            assertThat(cache.getOrCreate(new AuthUser(userId))).isSameAs(bundle);
            assertThat(embedding.queryCalls.get() - queries).isEqualTo(2);
            assertThat(embedding.indexingCalls).hasValue(startupCalls);
            for (var request : probe.requests) {
                var input = json.readTree(((UserMessage) request.messages().getLast()).singleText());
                assertThat(input.path("history")).isEmpty();
                if (input.has("evidence")) for (var evidence : input.path("evidence"))
                    assertThat(evidence.path("deviceType").asText()).isEqualTo(input.path("scope").path("deviceType").asText());
            }
        } finally { probe.release.countDown(); probe.entered = null; }
        long originalUser = userId;
        authenticate();
        var other = cache.getOrCreate(new AuthUser(userId));
        assertThat(userId).isNotEqualTo(originalUser);
        assertThat(other.direct()).isNotSameAs(bundle.direct());
        assertThat(other.analysis()).isNotSameAs(bundle.analysis());
        assertThat(other.enhanced()).isNotSameAs(bundle.enhanced());
        assertThat(embedding.indexingCalls).hasValue(startupCalls);
        verifyNoInteractions(devices);
    }

    @Test void lostExecutionClaimRejectsLateEnhancedCompletionAndReadDoesNotExecute() throws Exception {
        probe.entered = new CountDownLatch(1); probe.release = new CountDownLatch(1);
        try (var worker = Executors.newSingleThreadExecutor()) {
            var response = worker.submit(() -> restTemplate.exchange(url("/api/v1/sessions"), HttpMethod.POST,
                    new HttpEntity<>(Map.of("problem", "light 型号参数"), authHeaders(token)), String.class).getBody());
            assertThat(probe.entered.await(10, TimeUnit.SECONDS)).isTrue();
            Long id = jdbc.queryForObject("SELECT id FROM repair_session WHERE user_id=? ORDER BY id DESC LIMIT 1", Long.class, userId);
            String requestId = jdbc.queryForObject("SELECT active_workflow_request_id FROM repair_session WHERE id=?", String.class, id);
            jdbc.update("UPDATE workflow_execution SET lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE request_id=?", requestId);
            var detail = restTemplate.exchange(url("/api/v1/sessions/" + id), HttpMethod.GET,
                    new HttpEntity<>(authHeaders(token)), JsonNode.class).getBody();
            assertThat(detail.path("workflow").path("status").asText()).isEqualTo("RUNNING");
            probe.release.countDown();
            // The expired database fence rejects the late result; GET does not settle or replay work.
            response.get(10, TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("SELECT status FROM workflow_execution WHERE request_id=?", String.class, requestId)).isEqualTo("WAITING_RESUME");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_message WHERE session_id=? AND content LIKE '%document/light/%'",
                    Integer.class, id)).isZero();
        } finally { probe.release.countDown(); probe.entered = null; }
    }

    @Test void commonSenseFollowupPersistsExactlyVisibleStreamAndReusesUserServices() throws Exception {
        String first = post("", Map.of("problem", "色温和亮度有什么区别"));
        long id = sessionId(first);
        assertPersisted(id, first, 1);
        var bundle = cache.getOrCreate(new AuthUser(userId));
        String second = post("/" + id + "/messages", Map.of("content", "它对阅读有什么影响"));
        assertPersisted(id, second, 2);
        assertThat(cache.getOrCreate(new AuthUser(userId))).isSameAs(bundle);
        var history = restTemplate.exchange(url("/api/v1/sessions/" + id + "/messages"), HttpMethod.GET,
                new HttpEntity<>(authHeaders(token)), JsonNode.class).getBody();
        assertThat(history).hasSize(6); // Each turn records USER, committed STEP_RESULT and final CONCLUSION.
        assertThat(history.toString()).doesNotContain("answerContext", "queryText", "evidence");
        verifyNoInteractions(devices);
    }

    private String post(String suffix, Object body) {
        var response = restTemplate.exchange(url("/api/v1/sessions" + suffix), HttpMethod.POST,
                new HttpEntity<>(body, authHeaders(token)), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("event:conclusion").doesNotContain("event:error", "event:awaiting");
        return response.getBody();
    }
    private long sessionId(String sse) {
        var matcher = Pattern.compile("\"sessionId\":(\\d+)").matcher(sse);
        assertThat(matcher.find()).isTrue(); return Long.parseLong(matcher.group(1));
    }
    private void assertPersisted(long id, String sse, int round) throws Exception {
        StringBuilder visible = new StringBuilder();
        JsonNode conclusion = null;
        for (String block : sse.replace("\r\n", "\n").split("\n\n")) {
            String data = block.lines().filter(l -> l.startsWith("data:")).map(l -> l.substring(5)).findFirst().orElse(null);
            if (data == null) continue;
            var value = json.readTree(data);
            if (block.contains("event:token")) visible.append(value.isTextual() ? value.asText() : value.path("text").asText());
            if (block.contains("event:conclusion")) conclusion = value;
        }
        assertThat(visible).isNotEmpty();
        assertThat(conclusion).isNotNull();
        assertThat(conclusion.path("summary").asText()).isEqualTo(visible.toString());
        assertThat(jdbc.queryForObject("SELECT content FROM chat_message WHERE session_id=? AND role='ASSISTANT' ORDER BY id DESC LIMIT 1",
                String.class, id)).isEqualTo(visible.toString());
        var detail = restTemplate.exchange(url("/api/v1/sessions/" + id), HttpMethod.GET,
                new HttpEntity<>(authHeaders(token)), JsonNode.class).getBody();
        assertThat(detail.path("conclusion").path("summary").asText()).isEqualTo(visible.toString());
        assertThat(jdbc.queryForObject("SELECT processing_message_id FROM repair_session WHERE id=?", Long.class, id)).isNull();
    }
}
