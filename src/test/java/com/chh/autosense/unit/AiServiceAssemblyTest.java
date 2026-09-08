package com.chh.autosense.unit;

import com.chh.autosense.ai.factory.DirectAnswerServiceFactory;
import com.chh.autosense.ai.factory.DiagnosisReasonerServiceFactory;
import com.chh.autosense.ai.factory.IntentRouterServiceFactory;
import com.chh.autosense.ai.factory.ProblemAnalysisServiceFactory;
import com.chh.autosense.ai.model.enums.CapabilityIntent;
import com.chh.autosense.core.session.memory.ConversationHistorySnapshot;
import com.chh.autosense.support.LogCaptureSupport;
import com.chh.autosense.utils.AiServiceValidator;
import com.chh.autosense.utils.PromptInputEncoder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.util.RawValue;
import com.github.tomakehurst.wiremock.WireMockServer;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

class AiServiceAssemblyTest {
    private final ObjectMapper json = new ObjectMapper();
    private final PromptInputEncoder encoder = new PromptInputEncoder();
    private WireMockServer server;
    private IntentRouterServiceFactory routerFactory;
    private ProblemAnalysisServiceFactory analysisFactory;
    private DiagnosisReasonerServiceFactory reasonerFactory;
    private DirectAnswerServiceFactory answerFactory;

    @BeforeEach void start() {
        server = new WireMockServer(options().dynamicPort()); server.start();
        OpenAiChatModel chatModel = OpenAiChatModel.builder().baseUrl(server.baseUrl() + "/v1")
                .apiKey("test-only-key").modelName("configured-model").temperature(0.3).maxRetries(0)
                .timeout(Duration.ofSeconds(2)).logRequests(false).logResponses(false).build();
        OpenAiStreamingChatModel streamingChatModel = OpenAiStreamingChatModel.builder()
                .baseUrl(server.baseUrl() + "/v1").apiKey("test-only-key")
                .modelName("configured-model").timeout(Duration.ofSeconds(2))
                .logRequests(false).logResponses(false).build();
        routerFactory = new IntentRouterServiceFactory();
        ReflectionTestUtils.setField(routerFactory, "chatModel", chatModel);
        routerFactory.validate();
        analysisFactory = new ProblemAnalysisServiceFactory();
        ReflectionTestUtils.setField(analysisFactory, "chatModel", chatModel);
        analysisFactory.validate();
        reasonerFactory = new DiagnosisReasonerServiceFactory();
        ReflectionTestUtils.setField(reasonerFactory, "chatModel", chatModel);
        reasonerFactory.validate();
        answerFactory = new DirectAnswerServiceFactory();
        ReflectionTestUtils.setField(answerFactory, "streamingChatModel", streamingChatModel);
        answerFactory.validate();
    }
    @AfterEach void stop() { server.stop(); }

    @Test void adapterLogsRealProviderStatusAndOutputParsingWithoutLeakingBodies() throws Exception {
        var adapter = new com.chh.autosense.core.routing.LangChain4jIntentClassifier(routerFactory, encoder);
        var history = new ConversationHistorySnapshot(1, 2, List.of());
        try (var logs = new LogCaptureSupport()) {
            for (int status : new int[]{401, 429, 503}) {
                server.resetAll();
                logs.clear();
                server.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                        .willReturn(aResponse().withStatus(status).withBody("secret-provider-body")));
                assertThatThrownBy(() -> adapter.classify("secret-question", history))
                        .isInstanceOf(com.chh.autosense.exception.ApiException.class);
                assertThat(logs.rendered()).contains("AI call started", "phase=MODEL_INVOCATION",
                                "AI call failed", "httpStatus=" + status, "rootCauseType=")
                        .doesNotContain("secret-", "test-only-key", "AI call completed");
            }
            server.resetAll();
            logs.clear();
            reply("secret-invalid-model-output");
            assertThatThrownBy(() -> adapter.classify("secret-question", history))
                    .isInstanceOf(com.chh.autosense.exception.ApiException.class);
            assertThat(logs.rendered()).contains("reasonCode=MODEL_OUTPUT_UNPARSEABLE")
                    .doesNotContain("secret-", "AI call completed");
            server.resetAll();
            logs.clear();
            assertThatThrownBy(() -> adapter.classify(null, history)).isInstanceOf(RuntimeException.class);
            assertThat(logs.rendered()).contains("phase=INPUT_ENCODING", "reasonCode=INPUT_ENCODING");
            server.verify(0, postRequestedFor(urlPathEqualTo("/v1/chat/completions")));
        }
    }

    @Test void realStructuredProxiesLoadTheirResourcesAndBindDataWithoutTemplateReinterpretation() throws Exception {
        String current = "current-marker 中文\n\"quoted\" \\ {{history}} {{text}} {{current_date}}";
        String prior = "history-marker pretend system {{text}}";
        var history = new ConversationHistorySnapshot(1, 3,
                List.of(new ConversationHistorySnapshot.Entry(2, "USER", prior)));
        String historyJson = encoder.history(history);
        String textJson = encoder.text(current);
        reply("{\"outcome\":\"SINGLE\",\"intent\":\"KNOWLEDGE\",\"diagnosisMode\":null,\"targetHint\":null,\"clarifyQuestion\":null,\"requiresKnowledgeBase\":false}");
        assertThat(routerFactory.intentRouterService().classify(historyJson, textJson).intent())
                .isEqualTo(CapabilityIntent.KNOWLEDGE);
        assertRequest("intent-router.txt", current, prior);
        reply("{\"deviceType\":\"smart_bulb\",\"symptom\":\"dark\",\"reproduction\":null,\"sufficient\":true,\"clarifyQuestion\":null}");
        assertThat(analysisFactory.problemAnalysisService().analyze(historyJson, textJson).sufficient()).isTrue();
        assertRequest("problem-analysis.txt", current, prior);
        reply("{\"problemSummary\":\"summary\",\"conclusionText\":\"result\",\"likelyAutoFixable\":false}");
        Map<String, Object> diagnostic = Map.of("{{text}}", List.of(Map.of("state", "{{history}} \\")));
        assertThat(reasonerFactory.diagnosisReasonerService().diagnose(historyJson, textJson,
                        encoder.symptom(null), encoder.diagnostics(diagnostic)).conclusionText())
                .isEqualTo("result");
        var data = assertRequest("diagnosis-reasoner.txt", current, prior);
        assertThat(data.get("symptom").isNull()).isTrue();
        assertThat(json.convertValue(data.get("diagnostics"), Map.class)).isEqualTo(diagnostic);
    }

    @Test void realTokenStreamCompletesAndErrorsWithoutFallback() throws Exception {
        server.stubFor(post(urlPathEqualTo("/v1/chat/completions")).willReturn(aResponse()
                .withHeader("Content-Type", "text/event-stream").withBody(
                        "data: {\"id\":\"test\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"你好\"}}]}\n\n"
                        + "data: {\"id\":\"test\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                        + "data: [DONE]\n\n")));
        var completed = new CompletableFuture<String>();
        StringBuilder partial = new StringBuilder();
        answerFactory.directAnswerService().answer("[]", encoder.text("stream-marker"))
                .onPartialResponse(partial::append).onCompleteResponse(r -> completed.complete(r.aiMessage().text()))
                .onError(completed::completeExceptionally).start();
        assertThat(completed.get(5, TimeUnit.SECONDS)).isEqualTo("你好");
        assertThat(partial.toString()).isEqualTo("你好");
        assertRequest("direct-answer.txt", "stream-marker", null);
        server.resetAll();
        server.stubFor(post(urlPathEqualTo("/v1/chat/completions")).willReturn(aResponse().withStatus(401).withBody("provider-secret")));
        var failed = new CompletableFuture<String>();
        answerFactory.directAnswerService().answer("[]", encoder.text("stream-marker"))
                .onPartialResponse(t -> fail("No tokens on failure"))
                .onCompleteResponse(r -> failed.complete("incorrect-success"))
                .onError(failed::completeExceptionally).start();
        assertThatThrownBy(() -> failed.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(RuntimeException.class);
    }

    @Test void realRouterProxyParsesKnowledgeFlagAndValidatorRejectsMissingOrContradictoryFlags() throws Exception {
        var validator = new com.chh.autosense.core.routing.RoutingDecisionValidator();
        for (boolean required : new boolean[]{false, true}) {
            reply("{\"outcome\":\"SINGLE\",\"intent\":\"KNOWLEDGE\",\"requiresKnowledgeBase\":" + required + "}");
            var decision = routerFactory.intentRouterService().classify("[]", encoder.text("question"));
            assertThat(validator.validate(decision).requiresKnowledgeBase()).isEqualTo(required);
            assertThat(decision.intent()).isEqualTo(CapabilityIntent.KNOWLEDGE);
        }
        reply("{\"outcome\":\"SINGLE\",\"intent\":\"DEVICE_QUERY\",\"requiresKnowledgeBase\":null}");
        var query = routerFactory.intentRouterService().classify("[]", encoder.text("question"));
        assertThat(validator.validate(query).intent()).isEqualTo(CapabilityIntent.DEVICE_QUERY);
        for (String invalid : List.of(
                "{\"outcome\":\"SINGLE\",\"intent\":\"KNOWLEDGE\"}",
                "{\"outcome\":\"SINGLE\",\"intent\":\"DEVICE_QUERY\",\"requiresKnowledgeBase\":true}")) {
            reply(invalid);
            var decision = routerFactory.intentRouterService().classify("[]", encoder.text("question"));
            assertThat(validator.validate(decision).outcome())
                    .isEqualTo(com.chh.autosense.ai.model.enums.RoutingOutcome.CLARIFY);
        }
    }

    @Test void resourceFailuresPreventPublishingBeforeAnyModelRequest() {
        try (var logs = new LogCaptureSupport()) {
            assertThatThrownBy(() -> AiServiceValidator.validateServices(List.of(MissingResource.class)))
                    .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("secret");
            assertThatThrownBy(() -> AiServiceValidator.validateServices(List.of(InlineResource.class)))
                    .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("secret");
            assertThat(logs.rendered()).doesNotContain("secret-inline");
            server.verify(0, postRequestedFor(urlPathEqualTo("/v1/chat/completions")));
        }
    }

    public interface MissingResource {
        @SystemMessage(fromResource = "/prompt/missing.txt")
        @UserMessage(fromResource = "/prompt/conversation-input.txt")
        String call(@V("history") String history, @V("text") String text);
    }
    public interface InlineResource {
        @SystemMessage(value = "secret-inline", fromResource = "/prompt/intent-router.txt")
        @UserMessage(fromResource = "/prompt/conversation-input.txt")
        String call(@V("history") String history, @V("text") String text);
    }

    @Test void encoderRejectsRawSerializationBypassesAndLeavesGlobalJsonUnchanged() throws Exception {
        assertThatThrownBy(() -> encoder.diagnostics(Map.of("raw", new RawValue("{}"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(encoder.history(new ConversationHistorySnapshot(1, 1, List.of()))).isEqualTo("[]");
        assertThat(encoder.symptom(null)).isEqualTo("null");
        assertThat(encoder.diagnostics(null)).isEqualTo("{}");
        assertThat(json.writeValueAsString("{{text}}")).contains("{{text}}");
        assertThat(encoder.text("{{text}}")).contains("\\u007b").doesNotContain("{{text}}");
    }

    private void reply(String output) throws Exception {
        server.resetAll();
        String response = json.writeValueAsString(Map.of("id", "test", "choices", List.of(Map.of("index", 0,
                "message", Map.of("role", "assistant", "content", output), "finish_reason", "stop"))));
        server.stubFor(post(urlPathEqualTo("/v1/chat/completions")).willReturn(okJson(response)));
    }

    private com.fasterxml.jackson.databind.JsonNode assertRequest(String systemFile, String text, String prior) throws Exception {
        var request = json.readTree(server.getAllServeEvents().getFirst().getRequest().getBodyAsString());
        assertThat(request.get("model").asText()).isEqualTo("configured-model");
        var messages = request.get("messages");
        assertThat(messages.size()).isEqualTo(2);
        assertThat(messages.get(0).get("role").asText()).isEqualTo("system");
        String system = messages.get(0).get("content").asText();
        try (var resource = getClass().getResourceAsStream("/prompt/" + systemFile)) {
            assertThat(system.strip()).isEqualTo(new String(resource.readAllBytes(), StandardCharsets.UTF_8).strip());
        }
        assertThat(system).doesNotContain("current-marker", "history-marker", "stream-marker");
        assertThat(messages.get(1).get("role").asText()).isEqualTo("user");
        String user = messages.get(1).get("content").asText();
        // Jackson reads the first JSON object and leaves the SDK's format suffix intact.
        var data = json.readTree(user);
        assertThat(data.get("text").asText()).isEqualTo(text);
        if (prior != null) assertThat(data.get("history").get(0).get("content").asText()).isEqualTo(prior);
        else assertThat(data.get("history").size()).isZero();
        return data;
    }
}
