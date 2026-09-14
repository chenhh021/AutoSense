package com.chh.autosense.unit;

import com.chh.autosense.ai.DirectAnswerService;
import com.chh.autosense.ai.EnhancedAnswerService;
import com.chh.autosense.ai.factory.DirectAnswerServiceFactory;
import com.chh.autosense.ai.factory.EnhancedAnswerFactory;
import com.chh.autosense.service.knowledge.UserAiServiceCache.UserAiServices;
import com.chh.autosense.ai.factory.MockKnowledgeAiServicesFactory;
import com.chh.autosense.core.security.AuthUser;
import com.chh.autosense.service.knowledge.UserAiServiceCache;
import com.chh.autosense.support.DeterministicEmbeddingModel;
import com.chh.autosense.support.KnowledgeFixtures;
import com.chh.autosense.utils.PromptInputEncoder;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserAiServiceCacheTest {
    private EnhancedAnswerFactory enhancedFactory() {
        var factory = mock(EnhancedAnswerFactory.class);
        when(factory.problemAnalysisService()).thenAnswer(i -> mock(EnhancedAnswerService.class));
        when(factory.enhancedAnswerService()).thenAnswer(i -> mock(EnhancedAnswerService.class));
        return factory;
    }

    @Test void evictionDuringActualTokenStreamDoesNotInterruptCompletion() {
        var local = new MockKnowledgeAiServicesFactory();
        var direct = new DirectAnswerServiceFactory(local.knowledgeMockStreamingChatModel());
        var enhanced = new EnhancedAnswerFactory(local.knowledgeMockChatModel(), new InMemoryEmbeddingStore<>(),
                new DeterministicEmbeddingModel(), KnowledgeConfigurationTest.properties(Map.of()),
                KnowledgeFixtures.catalog(), new PromptInputEncoder());
        var clock = new AtomicLong();
        var cache = new UserAiServiceCache(KnowledgeConfigurationTest.properties(Map.of()), direct, enhanced, clock::get);
        var original = cache.getOrCreate(new AuthUser(1L));
        var completed = new java.util.concurrent.atomic.AtomicReference<String>();
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        original.direct().answer("[]", "\"question\"").onPartialResponse(token -> {
            clock.set(java.time.Duration.ofMinutes(31).toNanos());
            assertThat(cache.getOrCreate(new AuthUser(1L))).isNotSameAs(original);
        }).onCompleteResponse(response -> completed.set(response.aiMessage().text())).onError(failure::set).start();
        assertThat(failure).hasNullValue();
        assertThat(completed.get()).contains("本地模拟回答");
    }

    @Test void concurrentAccessCallsSpecializedFactoriesOnceAndExpirationIsAfterAccess() throws Exception {
        var clock = new AtomicLong();
        var direct = mock(DirectAnswerServiceFactory.class);
        var enhanced = enhancedFactory();
        when(direct.directAnswerService()).thenAnswer(i -> mock(DirectAnswerService.class));
        var cache = new UserAiServiceCache(KnowledgeConfigurationTest.properties(Map.of()), direct, enhanced, clock::get);
        var user = new AuthUser(1L);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var results = new ArrayList<java.util.concurrent.Future<UserAiServices>>();
            for (int i = 0; i < 24; i++) results.add(executor.submit(() -> cache.getOrCreate(user)));
            var original = results.getFirst().get();
            for (var result : results) assertThat(result.get()).isSameAs(original);
            verify(direct, times(1)).directAnswerService();
            verify(enhanced, times(1)).problemAnalysisService();
            verify(enhanced, times(1)).enhancedAnswerService();
            var other = cache.getOrCreate(new AuthUser(2L));
            assertThat(other.direct()).isNotSameAs(original.direct());
            assertThat(other.analysis()).isNotSameAs(original.analysis());
            assertThat(other.enhanced()).isNotSameAs(original.enhanced());
            clock.set(java.time.Duration.ofMinutes(29).toNanos());
            assertThat(cache.getOrCreate(user)).isSameAs(original);
            clock.set(java.time.Duration.ofMinutes(58).toNanos());
            assertThat(cache.getOrCreate(user)).isSameAs(original);
            clock.set(java.time.Duration.ofMinutes(89).toNanos());
            assertThat(cache.getOrCreate(user)).isNotSameAs(original);
            verifyNoInteractions(original.direct(), original.analysis(), original.enhanced());
            cache.invalidate(user.userId());
            assertThat(cache.getOrCreate(user)).isNotSameAs(original);
            verify(direct, times(4)).directAnswerService();
            verify(enhanced, times(4)).problemAnalysisService();
            verify(enhanced, times(4)).enhancedAnswerService();
        }
    }

    @Test void failuresHaveNoPartialEntryInvalidIdentityIsRejectedAndDefaultCapacityIsBounded() {
        var attempts = new AtomicInteger();
        var direct = mock(DirectAnswerServiceFactory.class);
        var enhanced = enhancedFactory();
        when(direct.directAnswerService()).thenAnswer(i -> {
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("failed factory");
            return mock(DirectAnswerService.class);
        });
        var cache = new UserAiServiceCache(KnowledgeConfigurationTest.properties(Map.of()), direct, enhanced, System::nanoTime);
        assertThatThrownBy(() -> cache.getOrCreate(new AuthUser(1L))).isInstanceOf(IllegalStateException.class);
        assertThat(cache.estimatedSize()).isZero();
        assertThat(cache.getOrCreate(new AuthUser(1L))).isNotNull();
        for (long id = 2; id <= 1005; id++) cache.getOrCreate(new AuthUser(id));
        cache.cleanUp();
        assertThat(cache.estimatedSize()).isLessThanOrEqualTo(1000);
        int before = attempts.get();
        assertThatThrownBy(() -> cache.getOrCreate(new AuthUser(0L))).isInstanceOf(IllegalArgumentException.class);
        assertThat(attempts).hasValue(before);
    }
}
