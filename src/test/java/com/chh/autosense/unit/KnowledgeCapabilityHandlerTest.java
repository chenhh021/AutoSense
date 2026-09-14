package com.chh.autosense.unit;

import com.chh.autosense.ai.*;
import com.chh.autosense.ai.model.*;
import com.chh.autosense.ai.model.enums.*;
import com.chh.autosense.core.routing.*;
import com.chh.autosense.core.security.AuthUser;
import com.chh.autosense.core.session.memory.ConversationHistorySnapshot;
import com.chh.autosense.domain.dto.*;
import com.chh.autosense.domain.enums.*;
import com.chh.autosense.exception.*;
import com.chh.autosense.service.knowledge.UserAiServiceCache;
import com.chh.autosense.support.*;
import com.chh.autosense.utils.PromptInputEncoder;
import dev.langchain4j.rag.content.*;
import dev.langchain4j.service.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KnowledgeCapabilityHandlerTest {
    final DirectAnswerer direct = mock(DirectAnswerer.class);
    final UserAiServiceCache cache = mock(UserAiServiceCache.class);
    final EnhancedAnswerService analysis = mock(EnhancedAnswerService.class);
    final EnhancedAnswerService enhanced = mock(EnhancedAnswerService.class);
    final PromptInputEncoder encoder = new PromptInputEncoder();
    final List<String> tokens = new ArrayList<>();
    KnowledgeCapabilityHandler handler;
    final AuthUser user = new AuthUser(7L);

    @BeforeEach void setup() {
        handler = new KnowledgeCapabilityHandler(direct, cache, encoder, KnowledgeFixtures.catalog(), KnowledgeConfigurationTest.properties(Map.of()));
        when(cache.getOrCreate(user)).thenReturn(new UserAiServiceCache.UserAiServices(mock(DirectAnswerService.class), analysis, enhanced));
        when(analysis.analyzeKnowledge(anyString(), anyString(), anyString())).thenReturn(scope(null, Set.of()));
        when(direct.answerKnowledge(any(), any(), any())).thenAnswer(i -> {
            i.<Consumer<String>>getArgument(2).accept("direct"); return CompletableFuture.completedFuture("direct");
        });
    }
    KnowledgeQueryAnalysis scope(String model, Set<KnowledgeMissingInformation> gaps) {
        return new KnowledgeQueryAnalysis("灯", "MI", model, "light query", gaps, "v2", "220 V");
    }
    CapabilityRequest request(boolean rag, LocalDateTime deadline) {
        return new CapabilityRequest(user, 10, 11, 1, 20, "original {{request}}", null,
                new ConversationHistorySnapshot(10, 20, List.of(new ConversationHistorySnapshot.Entry(1, "USER", "history"))),
                deadline, AssistantCapability.KNOWLEDGE, null, null, null, rag);
    }
    CapabilityRequest request() { return request(true, LocalDateTime.now().plusMinutes(1)); }
    Content source(String type, String product, double score) {
        return Content.from(KnowledgeFixtures.segment(type, product, "general.md", "12 W at 220 V"), Map.of(ContentMetadata.SCORE, score));
    }
    @SuppressWarnings("unchecked") Result<KnowledgeAnswer> result(List<Content> sources, List<String> ids, KnowledgeAnswerStatus status) {
        Result<KnowledgeAnswer> result = mock(Result.class);
        when(result.sources()).thenReturn(sources);
        when(result.content()).thenReturn(new KnowledgeAnswer("MI-MJDPL01YL: 12 W at 220 V", ids, status));
        return result;
    }
    String id(Content source) { return source.textSegment().metadata().getString("sourceId"); }

    @Test void commonSenseSkipsAnalysisAndRag() {
        var answer = handler.handle(request(false, LocalDateTime.now().plusMinutes(1)), tokens::add).toCompletableFuture().join();
        assertThat(answer.text()).isEqualTo("direct"); verifyNoInteractions(cache, analysis, enhanced);
    }
    @Test void unknownTypeSkipsSearchAndHasAccuratePrefix() {
        when(analysis.analyzeKnowledge(anyString(), anyString(), anyString())).thenReturn(new KnowledgeQueryAnalysis(
                "unknown", null, null, "query", Set.of(), null, null));
        String answer = handler.handle(request(), tokens::add).toCompletableFuture().join().text();
        assertThat(answer).contains("设备类型", "direct").doesNotContain("未检索到");
        assertThat(String.join("", tokens)).isEqualTo(answer); verifyNoInteractions(enhanced);
    }
    @Test void missingModelUsesOtherModelsAndPreservesScopeWithoutClarification() {
        var a = source("light", "MI-MJDPL01YL", .75); var b = source("light", "ACME-L2", .9);
        when(enhanced.answerKnowledge(anyString())).thenAnswer(i -> {
            var input = encoder.readKnowledgeRequest(i.getArgument(0));
            assertThat(input.scope().deviceType()).isEqualTo("light");
            assertThat(input.scope().model()).isNull();
            assertThat(input.scope().missingInformation()).contains(KnowledgeMissingInformation.MODEL);
            assertThat(input.scope().version()).isEqualTo("v2"); assertThat(input.scope().environment()).isEqualTo("220 V");
            assertThat(input.history()).containsExactly(Map.of("role", "USER", "content", "history"));
            return result(List.of(a,b), List.of(id(a),id(b)), KnowledgeAnswerStatus.ANSWERED);
        });
        var answer = handler.handle(request(), tokens::add).toCompletableFuture().join();
        assertThat(answer.text()).contains("没有当前型号设备信息", "MI-MJDPL01YL", "ACME-L2", id(a), id(b), "12 W at 220 V");
        assertThat(answer.kind()).isEqualTo(CapabilityResult.Kind.COMPLETE);
        assertThat(answer.conclusion().summary()).isEqualTo(String.join("", tokens)); verifyNoInteractions(direct);
    }
    @Test void matchingModelWithoutGapsNeedsNoCrossModelNotice() {
        when(analysis.analyzeKnowledge(anyString(), anyString(), anyString())).thenReturn(scope("MJDPL01YL", Set.of()));
        var source = source("light", "MI-MJDPL01YL", 1);
        doReturn(result(List.of(source), List.of(id(source)), KnowledgeAnswerStatus.ANSWERED)).when(enhanced).answerKnowledge(anyString());
        assertThat(handler.handle(request(), tokens::add).toCompletableFuture().join().text()).doesNotContain("没有当前型号设备信息");
    }
    @Test void onlyExplicitInsufficiencyFallsBackWithoutErrorLogs() {
        for (var reason : List.of(KnowledgeDirectAnswerReason.NO_MATCH, KnowledgeDirectAnswerReason.LOW_RELEVANCE)) {
            doThrow(new KnowledgeInsufficientException(reason)).when(enhanced).answerKnowledge(anyString());
            try (var logs = new LogCaptureSupport()) {
                var answer = handler.handle(request(), tokens::add).toCompletableFuture().join();
                assertThat(answer.text()).contains("未检索到足够相关的资料", "direct").doesNotContain("来源：");
                assertThat(logs.rendered()).doesNotContain("AI call failed", "level=ERROR", "original");
            }
        }
    }
    @Test void technicalFailuresNeverFallbackOrExposeOutput() {
        when(enhanced.answerKnowledge(anyString())).thenThrow(new IllegalStateException("secret-evidence"));
        try (var logs = new LogCaptureSupport()) {
            assertThatThrownBy(() -> handler.handle(request(), tokens::add).toCompletableFuture().join()).hasCauseInstanceOf(ApiException.class);
            assertThat(logs.rendered()).contains("enhancedAnswer", "AI call failed").doesNotContain("secret-evidence", "original");
        }
        assertThat(tokens).isEmpty(); verifyNoInteractions(direct);
    }
    @Test void inventedCrossTypeLowScoreAndIncompleteSourcesFailBeforeAnyToken() {
        var valid = source("light", "MI-MJDPL01YL", 1);
        var badHash = source("light", "MI-MJDPL01YL", 1); badHash.textSegment().metadata().put("documentHash", "invalid");
        for (var result : List.of(result(List.of(valid), List.of("fake"), KnowledgeAnswerStatus.ANSWERED),
                result(List.of(source("air", "ACME-A1", 1)), List.of(id(valid)), KnowledgeAnswerStatus.ANSWERED),
                result(List.of(source("light", "MI-MJDPL01YL", .74)), List.of(id(valid)), KnowledgeAnswerStatus.ANSWERED),
                result(List.of(badHash), List.of(id(valid)), KnowledgeAnswerStatus.ANSWERED))) {
            when(enhanced.answerKnowledge(anyString())).thenReturn(result);
            assertThatThrownBy(() -> handler.handle(request(), tokens::add).toCompletableFuture().join()).hasCauseInstanceOf(ApiException.class);
            assertThat(tokens).isEmpty();
        }
        verifyNoInteractions(direct);
    }
    @Test void conflictIsExplicitAndHasOnlyVerifiedSources() {
        var source = source("light", "MI-MJDPL01YL", 1);
        doReturn(result(List.of(source), List.of(id(source)), KnowledgeAnswerStatus.CONFLICT)).when(enhanced).answerKnowledge(anyString());
        var answer = handler.handle(request(), tokens::add).toCompletableFuture().join();
        assertThat(answer.text()).contains("资料存在冲突", id(source));
        assertThat(answer.status()).isEqualTo(SessionStatus.COMPLETED_ANSWERED);
    }
    @Test void expiredBeforeAnalysisAndAfterAnalysisNeverStartsFollowingModel() throws Exception {
        assertThatThrownBy(() -> handler.handle(request(true, LocalDateTime.now().minusSeconds(1)), tokens::add).toCompletableFuture().join())
                .hasCauseInstanceOf(ApiException.class);
        verifyNoInteractions(cache, analysis, enhanced, direct);
        when(analysis.analyzeKnowledge(anyString(), anyString(), anyString())).thenAnswer(i -> {
            Thread.sleep(100); return scope(null, Set.of());
        });
        assertThatThrownBy(() -> handler.handle(request(true, LocalDateTime.now().plusNanos(50_000_000)), tokens::add).toCompletableFuture().join())
                .hasCauseInstanceOf(ApiException.class);
        verifyNoInteractions(enhanced, direct); assertThat(tokens).isEmpty();
    }
}
