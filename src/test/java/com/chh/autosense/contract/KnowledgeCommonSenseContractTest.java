package com.chh.autosense.contract;

import com.chh.autosense.ai.factory.*;
import com.chh.autosense.ai.model.RoutingDecision;
import com.chh.autosense.ai.model.enums.*;
import com.chh.autosense.ai.rag.KnowledgeEmbeddingStore;
import com.chh.autosense.config.*;
import com.chh.autosense.controller.SessionController;
import com.chh.autosense.core.device.client.DeviceServiceClient;
import com.chh.autosense.core.routing.*;
import com.chh.autosense.core.security.*;
import com.chh.autosense.core.session.*;
import com.chh.autosense.core.session.memory.*;
import com.chh.autosense.domain.enums.SessionStatus;
import com.chh.autosense.mapper.*;
import com.chh.autosense.service.knowledge.UserAiServiceCache;
import com.chh.autosense.support.*;
import com.chh.autosense.utils.PromptInputEncoder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import dev.langchain4j.model.chat.*;
import dev.langchain4j.model.chat.response.*;
import dev.langchain4j.data.message.*;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.*;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@WebMvcTest(value = SessionController.class, properties = "autosense.llm.mode=mock")
@Import({SecurityConfig.class, BearerTokenAuthFilter.class, SessionOrchestrator.class, RoutingDecisionValidator.class,
        CapabilityDispatcher.class, KnowledgeCapabilityHandler.class, MockDirectAnswerer.class, PromptInputEncoder.class,
        DirectAnswerServiceFactory.class, EnhancedAnswerFactory.class, UserAiServiceCache.class,
        KnowledgeCommonSenseContractTest.Models.class})
@EnableConfigurationProperties({AssistantProperties.class, KnowledgeProperties.class})
class KnowledgeCommonSenseContractTest {
    @Autowired MockMvc mvc;
    @Autowired UserAiServiceCache cache;
    @Autowired Probe probe;
    @Autowired DeterministicEmbeddingModel embedding;
    @Autowired EmbeddingStore<TextSegment> store;
    @MockitoBean SessionProcessingService processing;
    @MockitoBean ConversationHistoryService history;
    @MockitoBean IntentClassifier classifier;
    @MockitoBean SessionLeaseService leases;
    @MockitoBean SessionContextStore contexts;
    @MockitoBean RepairSessionMapper sessions;
    @MockitoBean ChatMessageMapper messages;
    @MockitoBean DiagnosticSnapshotMapper snapshots;
    @MockitoBean DeviceServiceClient devices;
    @MockitoBean UserTokenResolver tokens;
    @MockitoBean(name = "applicationTaskExecutor") TaskExecutor executor;
    protected final AuthUser user = new AuthUser(701L);
    protected SessionProcessingService.Accepted accepted;
    protected final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();

    static class Probe {
        final List<dev.langchain4j.model.chat.request.ChatRequest> requests = new CopyOnWriteArrayList<>();
        final List<dev.langchain4j.model.chat.request.ChatRequest> direct = new CopyOnWriteArrayList<>();
        volatile String type = "light";
        volatile String model = null;
        volatile boolean invalidSource;
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class Models {
        @Bean Probe probe() { return new Probe(); }
        @Bean DeterministicEmbeddingModel embedding() { return new DeterministicEmbeddingModel(); }
        @Bean KnowledgeEmbeddingStore.Catalog catalog() { return KnowledgeFixtures.catalog(); }
        @Bean EmbeddingStore<TextSegment> store() {
            var memory = new InMemoryEmbeddingStore<TextSegment>();
            for (String product : List.of("MI-MJDPL01YL", "ACME-L2")) {
                var segment = KnowledgeFixtures.segment("light", product, "general.md", "light " + product + " 12 W at 220 V {{request}}");
                memory.add(DeterministicEmbeddingModel.vector(segment.text()), segment);
            }
            return spy(memory);
        }
        @Bean StreamingChatModel streaming(Probe probe) {
            return new StreamingChatModel() {
                @Override public void doChat(dev.langchain4j.model.chat.request.ChatRequest request, StreamingChatResponseHandler handler) {
                    probe.direct.add(request);
                    handler.onPartialResponse("常识回答"); handler.onPartialResponse("完成。");
                    handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("常识回答完成。")).build());
                }
            };
        }
        @Bean ChatModel chat(Probe probe) {
            return new ChatModel() {
                @Override public ChatResponse doChat(dev.langchain4j.model.chat.request.ChatRequest request) {
                    probe.requests.add(request);
                    try {
                        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                        var input = mapper.readTree(((UserMessage) request.messages().getLast()).singleText());
                        Map<String, Object> output = new LinkedHashMap<>();
                        if (input.has("evidence")) {
                            var ids = new ArrayList<String>();
                            input.path("evidence").forEach(e -> ids.add(e.path("sourceId").asText()));
                            output.put("answer", "依据 MI-MJDPL01YL 和 ACME-L2 资料：各型号均为 12 W，使用条件为 220 V。");
                            output.put("sourceIds", probe.invalidSource ? List.of("fabricated") : ids.stream().distinct().toList());
                            output.put("status", "ANSWERED");
                        } else {
                            output.put("deviceType", probe.type); output.put("brand", null); output.put("model", probe.model);
                            output.put("queryText", probe.type == null ? "unknown" : probe.type);
                            output.put("missingInformation", List.of()); output.put("version", "v2"); output.put("environment", "220 V");
                        }
                        return ChatResponse.builder().aiMessage(AiMessage.from(mapper.writeValueAsString(output))).build();
                    } catch (Exception e) { throw new AssertionError(e); }
                }
            };
        }
    }

    @BeforeEach void prepare() {
        cache.invalidate(user.userId());
        probe.requests.clear(); probe.direct.clear(); probe.type = "light"; probe.model = null; probe.invalidSource = false;
        embedding.queryCalls.set(0); embedding.queryTexts.clear(); clearInvocations(store);
        accepted = new SessionProcessingService.Accepted(10, user.userId(), 20, 30, 1, "什么是色温", null,
                LocalDateTime.now().plusMinutes(1), SessionStatus.CREATED);
        when(tokens.resolve("Bearer active")).thenReturn(user);
        doAnswer(i -> { i.<Runnable>getArgument(0).run(); return null; }).when(executor).execute(any());
        when(processing.create(eq(user), anyString())).thenAnswer(i -> accepted);
        when(processing.accept(eq(user), eq(10L), anyString(), isNull())).thenAnswer(i -> accepted);
        when(processing.advance(any(), any(), anyString())).thenReturn(true);
        when(processing.finish(any(), any(), anyString(), any(), any())).thenReturn(true);
        when(history.snapshot(any())).thenReturn(new ConversationHistorySnapshot(10, 20, List.of()));
        when(leases.acquire(anyLong(), anyLong())).thenReturn(mock(SessionLeaseService.Lease.class));
        route(false);
    }
    protected void route(boolean rag) {
        when(classifier.classify(anyString(), any())).thenReturn(new RoutingDecision(RoutingOutcome.SINGLE,
                CapabilityIntent.KNOWLEDGE, null, null, null, rag));
    }
    protected String post(String suffix, String body) throws Exception {
        MvcResult result = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/sessions" + suffix).header("Authorization", "Bearer active")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        if (result.getRequest().isAsyncStarted()) result = mvc.perform(asyncDispatch(result)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }
    @Test void unboundCommonSenseAndFollowupUseCachedStreamWithoutQueryOrDeviceAccess() throws Exception {
        String first = post("", "{\"problem\":\"什么是色温\"}");
        assertThat(first).contains("event:token", "event:conclusion", "常识回答完成。").doesNotContain("event:awaiting", "event:error");
        var bundle = cache.getOrCreate(user);
        accepted = new SessionProcessingService.Accepted(10, user.userId(), 24, 30, 2, "它与亮度的区别呢", null,
                LocalDateTime.now().plusMinutes(1), SessionStatus.COMPLETED_ANSWERED);
        when(history.snapshot(any())).thenReturn(new ConversationHistorySnapshot(10, 24, List.of(
                new ConversationHistorySnapshot.Entry(20, "USER", "什么是色温"),
                new ConversationHistorySnapshot.Entry(21, "ASSISTANT", "常识回答完成。"))));
        assertThat(post("/10/messages", "{\"content\":\"它与亮度的区别呢\"}")).contains("event:conclusion");
        assertThat(cache.getOrCreate(user)).isSameAs(bundle);
        assertThat(probe.direct).hasSize(2); assertThat(probe.requests).isEmpty();
        var input = ((UserMessage) probe.direct.getLast().messages().getLast()).singleText();
        assertThat(input).contains("什么是色温", "常识回答完成。", "它与亮度的区别呢", "COMMON_SENSE");
        assertThat(embedding.queryCalls).hasValue(0); verify(store, never()).search(any()); verifyNoInteractions(devices);
        verify(processing).finish(eq(accepted), eq(SessionStatus.COMPLETED_ANSWERED), eq("常识回答完成。"),
                argThat(c -> c.summary().equals("常识回答完成。")), isNull());
    }
}
