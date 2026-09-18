package com.chh.autosense.unit;

import com.chh.autosense.ai.*;
import com.chh.autosense.ai.model.*;
import com.chh.autosense.ai.model.enums.*;
import com.chh.autosense.core.security.AuthUser;
import com.chh.autosense.core.session.memory.ConversationHistorySnapshot;
import com.chh.autosense.domain.enums.*;
import com.chh.autosense.exception.KnowledgeInsufficientException;
import com.chh.autosense.service.knowledge.*;
import com.chh.autosense.support.*;
import com.chh.autosense.utils.PromptInputEncoder;
import dev.langchain4j.rag.content.*;
import dev.langchain4j.service.*;
import org.junit.jupiter.api.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class KnowledgeWorkflowServiceTest {
    final UserAiServiceCache cache = mock(UserAiServiceCache.class);
    final DirectAnswerService direct = mock(DirectAnswerService.class);
    final EnhancedAnswerService analysis = mock(EnhancedAnswerService.class), enhanced = mock(EnhancedAnswerService.class);
    final TokenStream tokens = mock(TokenStream.class);
    final PromptInputEncoder encoder = new PromptInputEncoder();
    KnowledgeWorkflowService service;
    @BeforeEach void setup() {
        service = new KnowledgeWorkflowService(cache, encoder, KnowledgeFixtures.catalog(), KnowledgeConfigurationTest.properties(Map.of()));
        when(cache.getOrCreate(any())).thenReturn(new UserAiServiceCache.UserAiServices(direct, analysis, enhanced));
        when(direct.answerKnowledge(anyString(), anyString(), anyString())).thenReturn(tokens);
        when(analysis.analyzeKnowledge(anyString(), anyString(), anyString())).thenReturn(new KnowledgeQueryAnalysis("light", "MI", null, "query", Set.of(KnowledgeMissingInformation.MODEL), "v2", "220 V"));
    }
    KnowledgeWorkflowService.Request request(boolean rag) {
        return new KnowledgeWorkflowService.Request(new AuthUser(1L), "question {{history}}", new ConversationHistorySnapshot(1, 2,
                List.of(new ConversationHistorySnapshot.Entry(1, "USER", "prior"))), rag, Instant.now().plusSeconds(30));
    }
    Content source(String type, String model, double score) {
        return Content.from(KnowledgeFixtures.segment(type, model, "general.md", "12 W"), Map.of(ContentMetadata.SCORE, score));
    }
    @SuppressWarnings("unchecked") Result<KnowledgeAnswer> result(Content source, String id, KnowledgeAnswerStatus status) {
        Result<KnowledgeAnswer> result = mock(Result.class);
        when(result.sources()).thenReturn(List.of(source)); when(result.content()).thenReturn(new KnowledgeAnswer("12 W", List.of(id), status)); return result;
    }
    @Test void commonSenseUsesCachedDirectProxyWithoutAnalysisOrRetrieval() throws Exception {
        var response = service.answer(request(false));
        assertThat(response.stream()).isSameAs(tokens); assertThat(response.prefix()).isEmpty();
        verifyNoInteractions(analysis, enhanced);
    }
    @Test void unknownTypeAndExplicitInsufficiencyUseAccurateDirectFallbacks() throws Exception {
        for (var reason : List.of(KnowledgeDirectAnswerReason.NO_MATCH, KnowledgeDirectAnswerReason.LOW_RELEVANCE)) {
            doThrow(new KnowledgeInsufficientException(reason)).when(enhanced).answerKnowledge(anyString());
            var response = service.answer(request(true));
            assertThat(response.stream()).isSameAs(tokens); assertThat(response.prefix()).contains("未检索到足够相关的资料");
        }
        when(analysis.analyzeKnowledge(anyString(), anyString(), anyString())).thenReturn(new KnowledgeQueryAnalysis("unknown", null, null, "query", Set.of(), null, null));
        clearInvocations(enhanced);
        assertThat(service.answer(request(true)).prefix()).contains("无法确定设备类型"); verifyNoInteractions(enhanced);
    }
    @Test void crossModelNoticeAndSourceReferencesAreReturnedForPersistence() throws Exception {
        var source = source("light", "MI-MJDPL01YL", .9); String id = source.textSegment().metadata().getString("sourceId");
        when(enhanced.answerKnowledge(anyString())).thenAnswer(call -> {
            var envelope = encoder.readKnowledgeRequest(call.getArgument(0));
            assertThat(envelope.scope().version()).isEqualTo("v2"); assertThat(envelope.scope().environment()).isEqualTo("220 V");
            assertThat(envelope.history()).containsExactly(Map.of("role", "USER", "content", "prior"));
            return result(source, id, KnowledgeAnswerStatus.ANSWERED);
        });
        var response = service.answer(request(true));
        assertThat(response.data().get("answer").toString()).contains("没有当前型号设备信息", "MI-MJDPL01YL", id, "12 W");
        assertThat((List<?>) response.data().get("sources")).hasSize(1); assertThat(response.stream()).isNull(); verifyNoInteractions(direct);
    }
    @Test void invalidOrUnrelatedEvidenceAndTechnicalFailuresNeverFallback() throws Exception {
        for (var source : List.of(source("air", "ACME-A1", 1), source("light", "MI-MJDPL01YL", .1))) {
            doReturn(result(source, source.textSegment().metadata().getString("sourceId"), KnowledgeAnswerStatus.ANSWERED)).when(enhanced).answerKnowledge(anyString());
            assertThatThrownBy(() -> service.answer(request(true))).isInstanceOf(RuntimeException.class);
        }
        when(enhanced.answerKnowledge(anyString())).thenThrow(new IllegalStateException("private evidence"));
        assertThatThrownBy(() -> service.answer(request(true))).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(direct);
    }

    @Test void invalidSourceIdentityOrHashNeverPublishesAnAnswer() {
        var valid = source("light", "MI-MJDPL01YL", 1);
        var badHash = source("light", "MI-MJDPL01YL", 1);
        badHash.textSegment().metadata().put("documentHash", "invalid");
        for (var response : List.of(result(valid, "fabricated", KnowledgeAnswerStatus.ANSWERED),
                result(badHash, valid.textSegment().metadata().getString("sourceId"), KnowledgeAnswerStatus.ANSWERED))) {
            when(enhanced.answerKnowledge(anyString())).thenReturn(response);
            assertThatThrownBy(() -> service.answer(request(true))).isInstanceOf(RuntimeException.class);
        }
        verifyNoInteractions(direct);
    }

    @Test void matchingModelWithoutGapsAvoidsCrossModelNoticeAndConflictRetainsSources() throws Exception {
        when(analysis.analyzeKnowledge(anyString(), anyString(), anyString())).thenReturn(
                new KnowledgeQueryAnalysis("light", "MI", "MI-MJDPL01YL", "query", Set.of(), null, null));
        var source = source("light", "MI-MJDPL01YL", 1); String id = source.textSegment().metadata().getString("sourceId");
        doReturn(result(source, id, KnowledgeAnswerStatus.ANSWERED)).when(enhanced).answerKnowledge(anyString());
        assertThat(service.answer(request(true)).data().get("answer").toString()).contains(id).doesNotContain("没有当前型号设备信息");
        doReturn(result(source, id, KnowledgeAnswerStatus.CONFLICT)).when(enhanced).answerKnowledge(anyString());
        assertThat(service.answer(request(true)).data().get("answer").toString()).contains("资料存在冲突", id);
    }

    @Test void deadlineBeforeOrDuringAnalysisPreventsFurtherCalls() {
        var base = request(true);
        assertThatThrownBy(() -> service.answer(new KnowledgeWorkflowService.Request(base.user(), base.content(),
                base.history(), true, Instant.now().minusSeconds(1)))).isInstanceOf(java.util.concurrent.TimeoutException.class);
        verifyNoInteractions(cache, analysis, enhanced, direct);
        when(analysis.analyzeKnowledge(anyString(), anyString(), anyString())).thenAnswer(call -> {
            Thread.sleep(150);
            return new KnowledgeQueryAnalysis("light", null, null, "query", Set.of(), null, null);
        });
        assertThatThrownBy(() -> service.answer(new KnowledgeWorkflowService.Request(base.user(), base.content(),
                base.history(), true, Instant.now().plusMillis(100)))).isInstanceOf(java.util.concurrent.TimeoutException.class);
        verifyNoInteractions(enhanced, direct);
    }
}
