package com.chh.autosense.contract;

import com.chh.autosense.ai.factory.*;
import com.chh.autosense.ai.rag.KnowledgeEmbeddingStore;
import com.chh.autosense.config.*;
import com.chh.autosense.core.security.AuthUser;
import com.chh.autosense.core.session.memory.*;
import com.chh.autosense.service.knowledge.*;
import com.chh.autosense.support.*;
import com.chh.autosense.utils.PromptInputEncoder;
import com.chh.autosense.graph.node.AiTokenStreamAdapter;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import dev.langchain4j.model.chat.*;
import dev.langchain4j.model.chat.response.*;
import dev.langchain4j.data.message.*;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.*;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringJUnitConfig(KnowledgeCommonSenseContractTest.Services.class)
class KnowledgeCommonSenseContractTest {
    @Configuration(proxyBeanMethods = false)
    @Import({Models.class, PromptInputEncoder.class, DirectAnswerServiceFactory.class,
            EnhancedAnswerFactory.class, UserAiServiceCache.class, KnowledgeWorkflowService.class})
    @EnableConfigurationProperties(KnowledgeProperties.class)
    static class Services { }
    @Autowired KnowledgeWorkflowService knowledge;
    @Autowired UserAiServiceCache cache;
    @Autowired Probe probe;
    @Autowired DeterministicEmbeddingModel embedding;
    @Autowired EmbeddingStore<TextSegment> store;
    protected final AuthUser user = new AuthUser(701L);
    protected final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
    protected Map<String, Object> answerData;
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
        embedding.queryCalls.set(0); embedding.queryTexts.clear(); reset(store);
    }
    protected String answer(boolean rag, String text, List<ConversationHistorySnapshot.Entry> history) throws Exception {
        var result = knowledge.answer(new KnowledgeWorkflowService.Request(user, text,
                new ConversationHistorySnapshot(10, 24, history), rag, Instant.now().plusSeconds(30)));
        answerData = result.data();
        return result.stream() == null ? result.data().get("answer").toString()
                : AiTokenStreamAdapter.collect(result.stream(), result.prefix(), Duration.ofSeconds(30));
    }
    @Test void commonSenseFollowupUsesSameUserServicesAndHistoryWithoutRetrieval() throws Exception {
        String first = answer(false, "question-one", List.of());
        assertThat(first).isNotBlank();
        var bundle = cache.getOrCreate(user);
        assertThat(answer(false, "question-two", List.of(
                new ConversationHistorySnapshot.Entry(20, "USER", "question-one"),
                new ConversationHistorySnapshot.Entry(21, "ASSISTANT", first)))).isEqualTo(first);
        assertThat(cache.getOrCreate(user)).isSameAs(bundle);
        assertThat(probe.direct).hasSize(2); assertThat(probe.requests).isEmpty();
        String input = ((UserMessage) probe.direct.getLast().messages().getLast()).singleText();
        assertThat(input).contains("question-one", "question-two", first, "COMMON_SENSE");
        assertThat(embedding.queryCalls).hasValue(0); verify(store, never()).search(any());
    }
}
