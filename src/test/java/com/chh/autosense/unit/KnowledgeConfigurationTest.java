package com.chh.autosense.unit;

import com.chh.autosense.config.AssistantProperties;
import com.chh.autosense.config.KnowledgeEmbeddingProperties;
import com.chh.autosense.config.KnowledgeProperties;
import com.chh.autosense.config.LlmProperties;
import com.chh.autosense.ai.rag.KnowledgeEmbeddingStore;
import com.chh.autosense.utils.PromptInputEncoder;
import com.chh.autosense.config.KnowledgeEmbeddingConfig;
import com.chh.autosense.ai.factory.MockKnowledgeAiServicesFactory;
import com.chh.autosense.core.security.AuthUser;
import com.chh.autosense.service.knowledge.*;
import com.chh.autosense.support.KnowledgeFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class KnowledgeConfigurationTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({KnowledgeProperties.class, KnowledgeEmbeddingProperties.class,
            LlmProperties.class, AssistantProperties.class})
    @Import({KnowledgeEmbeddingStore.class, KnowledgeEmbeddingConfig.class, MockKnowledgeAiServicesFactory.class,
            UserAiServiceCache.class, PromptInputEncoder.class,
            com.chh.autosense.ai.factory.DirectAnswerServiceFactory.class, com.chh.autosense.ai.factory.EnhancedAnswerFactory.class})
    static class Assembly { }

    private ApplicationContextRunner context() {
        return new ApplicationContextRunner().withUserConfiguration(Assembly.class).withPropertyValues(
                "autosense.llm.mode=mock", "autosense.llm.temperature=0", "autosense.llm.timeout-seconds=30",
                "autosense.knowledge.embedding.provider=mock");
    }

    @Test void startupPublishesCompleteProductionResourcesAndDoesNotWarmUserCache() {
        context().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var index = ctx.getBean(dev.langchain4j.store.embedding.EmbeddingStore.class);
            assertThat(index).isInstanceOf(dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore.class);
            assertThat(ctx.getBean(KnowledgeEmbeddingStore.Catalog.class).productsByType().get("light"))
                    .contains("MI-MJDPL01YL");
            assertThat(ctx.getBean(dev.langchain4j.model.embedding.EmbeddingModel.class).dimension()).isEqualTo(64);
            var cache = ctx.getBean(UserAiServiceCache.class);
            assertThat(cache.estimatedSize()).isZero();
            var pair = cache.getOrCreate(new AuthUser(42L));
            assertThat(pair.analysis().analyze("[]", "\"question\"").sufficient()).isFalse();
            assertThat(ctx.getBean(com.chh.autosense.ai.factory.EnhancedAnswerFactory.class))
                    .extracting(factory -> org.springframework.test.util.ReflectionTestUtils.getField(factory, "store"))
                    .isSameAs(index);
            assertThat(cache.getOrCreate(new AuthUser(43L)).enhanced()).isNotSameAs(pair.enhanced());
            assertThat(ctx.getBean(dev.langchain4j.store.embedding.EmbeddingStore.class)).isSameAs(index);
        });
    }

    @Test void failedKnowledgeImportPreventsSpringContextStartup() throws Exception {
        context().withPropertyValues("autosense.knowledge.documents.max-total-bytes=1").run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure()).hasStackTraceContaining("BYTE_LIMIT");
                });
        context().withPropertyValues("autosense.knowledge.embedding.provider=openai-compatible").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).hasStackTraceContaining("embedding connection is not configured");
        });
    }

    public static KnowledgeProperties properties(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("autosense.knowledge", Bindable.of(KnowledgeProperties.class));
    }

    public static KnowledgeEmbeddingProperties embedding(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("autosense.knowledge.embedding", Bindable.of(KnowledgeEmbeddingProperties.class));
    }

    @Test void defaultsAndInvalidBoundsAreValidated() {
        var p = properties(Map.of());
        assertThat(p.serviceCache().expireAfterAccess()).isEqualTo(Duration.ofMinutes(30));
        assertThat(p.serviceCache().maximumSize()).isEqualTo(1000);
        assertThat(p.documents().maxTotalBytes()).isEqualTo(33554432);
        assertThat(p.retrieval().minScore()).isEqualTo(0.75);
        assertThat(p.documents().maxSegments()).isEqualTo(10000);
        assertThat(p.documents().startupTimeoutSeconds()).isEqualTo(300);
        assertThat(p.documents().maxSegmentChars()).isEqualTo(1000);
        assertThat(p.documents().overlapChars()).isEqualTo(150);
        assertThat(p.retrieval().topK()).isEqualTo(4);
        for (var field : Map.of("service-cache.maximum-size", "0", "service-cache.expire-after-access", "0s",
                "documents.overlap-chars", "1000", "retrieval.min-score", "NaN",
                "documents.max-segments", "0", "store.type", "external", "retrieval.top-k", "0").entrySet()) {
            assertThatThrownBy(() -> properties(Map.of("autosense.knowledge." + field.getKey(), field.getValue())))
                    .as(field.getKey()).isInstanceOf(Exception.class);
        }
        assertThatThrownBy(() -> properties(Map.of("autosense.knowledge.type-aliases.[LAMP]", "light",
                "autosense.knowledge.type-aliases.[lamp]", "air"))).isInstanceOf(Exception.class);
    }

    @Test void providerAndCumulativeBudgetNeverSilentlyFallBack() {
        var llm = new LlmProperties(null, null, null, 0.0, 30, "mock", 0);
        var timing = new AssistantProperties(120, 30, 10, 1800);
        var mock = embedding(Map.of("autosense.knowledge.embedding.provider", "mock"));
        mock.validate(llm, timing);
        assertThatThrownBy(() -> mock.validate(llm, new AssistantProperties(105, 30, 10, 1800)))
                .isInstanceOf(IllegalArgumentException.class); // Equality at the absolute budget is rejected.
        mock.validate(llm, new AssistantProperties(106, 30, 10, 1800));
        assertThatThrownBy(() -> embedding(Map.of()).validate(llm, timing)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> embedding(Map.of("autosense.knowledge.embedding.provider", "unknown")))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> mock.validate(llm, new AssistantProperties(100, 30, 10, 1800)))
                .isInstanceOf(IllegalArgumentException.class);
        var real = new LlmProperties("http://localhost:1234/v1", "test", "chat", 0.0, 30, "real", 0);
        assertThatThrownBy(() -> mock.validate(real, timing)).isInstanceOf(IllegalArgumentException.class);
        var configured = embedding(Map.of("autosense.knowledge.embedding.provider", "openai-compatible",
                "autosense.knowledge.embedding.base-url", "http://localhost:1234/v1",
                "autosense.knowledge.embedding.api-key", "secret-test-key",
                "autosense.knowledge.embedding.model-name", "separate-embedding"));
        configured.validate(real, timing);
        assertThat(configured.modelName()).isNotEqualTo(real.modelName());
        assertThat(configured.toString()).doesNotContain("secret-test-key");
        for (var field : Map.of("dimensions", "0", "timeout-seconds", "0", "max-retries", "4",
                "max-segments-per-batch", "0").entrySet()) {
            assertThatThrownBy(() -> embedding(Map.of("autosense.knowledge.embedding.provider", "mock",
                    "autosense.knowledge.embedding." + field.getKey(), field.getValue()))).isInstanceOf(Exception.class);
        }
    }

    @Test void realEmbeddingClientUsesConfiguredModelDimensionsAndBatchLimit() {
        var server = new com.github.tomakehurst.wiremock.WireMockServer(
                com.github.tomakehurst.wiremock.core.WireMockConfiguration.options().dynamicPort());
        server.start();
        try {
            server.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
                    com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo("/v1/embeddings"))
                    .willReturn(com.github.tomakehurst.wiremock.client.WireMock.okJson(
                            "{\"data\":[{\"index\":0,\"embedding\":[1,0,0]},{\"index\":1,\"embedding\":[0,1,0]}],"
                                    + "\"model\":\"separate-embedding\",\"usage\":{\"prompt_tokens\":2,\"total_tokens\":2}}")));
            var config = embedding(Map.of("autosense.knowledge.embedding.provider", "openai-compatible",
                    "autosense.knowledge.embedding.base-url", server.baseUrl() + "/v1",
                    "autosense.knowledge.embedding.api-key", "test-only-embedding-key",
                    "autosense.knowledge.embedding.model-name", "separate-embedding",
                    "autosense.knowledge.embedding.dimensions", "3",
                    "autosense.knowledge.embedding.max-segments-per-batch", "2"));
            var model = new KnowledgeEmbeddingConfig().knowledgeEmbeddingModel(config,
                    new LlmProperties(null, null, null, 0.0, 30, "mock", 0),
                    new AssistantProperties(120, 30, 10, 1800));
            var segments = java.util.stream.IntStream.range(0, 4)
                    .mapToObj(i -> dev.langchain4j.data.segment.TextSegment.from("fixture-" + i)).toList();
            assertThat(model.embedAll(segments).content()).hasSize(4);
            assertThat(model.dimension()).isEqualTo(3);
            server.verify(2, com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(
                    com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo("/v1/embeddings"))
                    .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath("$.model",
                            com.github.tomakehurst.wiremock.client.WireMock.equalTo("separate-embedding")))
                    .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath("$.dimensions",
                            com.github.tomakehurst.wiremock.client.WireMock.equalTo("3"))));
        } finally {
            server.stop();
        }
    }

    @Test void vectorValidationIsDistinguishedFromProviderFailureWithoutLeakingData() {
        try (var logs = new com.chh.autosense.support.LogCaptureSupport()) {
            dev.langchain4j.model.embedding.EmbeddingModel broken = segments ->
                    dev.langchain4j.model.output.Response.from(java.util.List.of());
            assertThatThrownBy(() -> KnowledgeEmbeddingConfig.validated(broken, 3).embed("secret-question"))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(logs.rendered()).contains("phase=OUTPUT_VALIDATION", "reasonCode=OUTPUT_VALIDATION")
                    .doesNotContain("secret-question");
            logs.clear();
            dev.langchain4j.model.embedding.EmbeddingModel failingProvider =
                    segments -> { throw new IllegalStateException("secret-provider"); };
            var model = KnowledgeEmbeddingConfig.validated(failingProvider, 3);
            assertThatThrownBy(() -> model.embed("secret-question")).isInstanceOf(IllegalStateException.class);
            assertThat(logs.rendered()).contains("phase=MODEL_INVOCATION").doesNotContain("secret-provider", "secret-question");
        }
    }
}
