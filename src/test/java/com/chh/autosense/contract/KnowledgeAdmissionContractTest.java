package com.chh.autosense.contract;

import com.chh.autosense.ai.DirectAnswerService;
import com.chh.autosense.ai.EnhancedAnswerService;
import com.chh.autosense.ai.factory.DirectAnswerServiceFactory;
import com.chh.autosense.ai.factory.EnhancedAnswerFactory;
import com.chh.autosense.ai.model.RoutingDecision;
import com.chh.autosense.ai.model.enums.RoutingOutcome;
import com.chh.autosense.config.AssistantProperties;
import com.chh.autosense.config.KnowledgeProperties;
import com.chh.autosense.controller.SessionController;
import com.chh.autosense.core.routing.*;
import com.chh.autosense.core.security.*;
import com.chh.autosense.core.session.*;
import com.chh.autosense.core.session.memory.ConversationHistoryService;
import com.chh.autosense.core.session.memory.ConversationHistorySnapshot;
import com.chh.autosense.domain.enums.SessionStatus;
import com.chh.autosense.exception.ApiException;
import com.chh.autosense.exception.ErrorCode;
import com.chh.autosense.mapper.*;
import com.chh.autosense.service.knowledge.*;
import com.github.benmanes.caffeine.cache.Ticker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SessionController.class)
@Import({SecurityConfig.class, BearerTokenAuthFilter.class, SessionOrchestrator.class,
        RoutingDecisionValidator.class, KnowledgeAdmissionContractTest.CacheConfig.class})
@EnableConfigurationProperties({AssistantProperties.class, KnowledgeProperties.class})
class KnowledgeAdmissionContractTest {
    @Autowired MockMvc mvc;
    @Autowired UserAiServiceCache cache;
    @Autowired FactoryProbe factory;
    @MockitoBean SessionProcessingService processing;
    @MockitoBean ConversationHistoryService history;
    @MockitoBean IntentClassifier classifier;
    @MockitoBean CapabilityDispatcher dispatcher;
    @MockitoBean SessionLeaseService leases;
    @MockitoBean SessionContextStore contexts;
    @MockitoBean RepairSessionMapper sessions;
    @MockitoBean ChatMessageMapper messages;
    @MockitoBean DiagnosticSnapshotMapper snapshots;
    @MockitoBean UserTokenResolver tokens;
    @MockitoBean(name = "applicationTaskExecutor") TaskExecutor executor;
    private static final AtomicLong USER_IDS = new AtomicLong(100);
    private AuthUser user;
    private SessionProcessingService.Accepted accepted;
    private int before;

    static final class FactoryProbe {
        final AtomicInteger creations = new AtomicInteger();
        boolean fail;
        DirectAnswerService createDirect() {
            creations.incrementAndGet();
            if (fail) throw new IllegalStateException("local construction failed");
            return mock(DirectAnswerService.class);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CacheConfig {
        @Bean FactoryProbe factoryProbe() { return new FactoryProbe(); }
        @Bean UserAiServiceCache cache(KnowledgeProperties properties, FactoryProbe probe) {
            var direct = mock(DirectAnswerServiceFactory.class);
            var enhanced = mock(EnhancedAnswerFactory.class);
            when(direct.directAnswerService()).thenAnswer(i -> probe.createDirect());
            when(enhanced.problemAnalysisService()).thenAnswer(i -> mock(EnhancedAnswerService.class));
            when(enhanced.enhancedAnswerService()).thenAnswer(i -> mock(EnhancedAnswerService.class));
            return new UserAiServiceCache(properties, direct, enhanced, Ticker.systemTicker());
        }
    }

    @BeforeEach void prepare() {
        user = new AuthUser(USER_IDS.incrementAndGet());
        before = factory.creations.get();
        factory.fail = false;
        accepted = new SessionProcessingService.Accepted(10, user.userId(), 20, 30, 1, "poetry", null,
                LocalDateTime.now().plusMinutes(2), SessionStatus.CREATED);
        when(tokens.resolve("Bearer active")).thenReturn(user);
        doAnswer(invocation -> { invocation.<Runnable>getArgument(0).run(); return null; }).when(executor).execute(any());
        when(processing.create(eq(user), anyString())).thenReturn(accepted);
        when(processing.accept(eq(user), eq(10L), anyString(), isNull())).thenReturn(accepted);
        when(processing.finish(any(), any(), anyString(), any(), any())).thenReturn(true);
        when(history.snapshot(any())).thenReturn(new ConversationHistorySnapshot(10, 20, List.of()));
        when(classifier.classify(anyString(), any())).thenReturn(
                new RoutingDecision(RoutingOutcome.OUT_OF_SCOPE, null, null, null, null, null));
        when(leases.acquire(anyLong(), anyLong())).thenReturn(mock(SessionLeaseService.Lease.class));
    }

    private String create() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/sessions").header("Authorization", "Bearer active")
                .contentType(MediaType.APPLICATION_JSON).content("{\"problem\":\"poetry\"}")).andReturn();
        if (result.getRequest().isAsyncStarted()) result = mvc.perform(asyncDispatch(result)).andReturn();
        return result.getResponse().getContentAsString();
    }

    @Test void firstNonKnowledgeConversationInitializesAfterGuardAndSubsequentRequestsReuse() throws Exception {
        when(classifier.classify(anyString(), any())).thenAnswer(invocation -> {
            assertThat(factory.creations).hasValue(before + 1);
            return new RoutingDecision(RoutingOutcome.OUT_OF_SCOPE, null, null, null, null, null);
        });
        assertThat(create()).contains("conclusion");
        var order = inOrder(processing, leases, classifier);
        order.verify(processing).create(user, "poetry");
        order.verify(leases).acquire(10, 20);
        order.verify(classifier).classify(anyString(), any());
        assertThat(factory.creations).hasValue(before + 1);
        create();
        assertThat(factory.creations).hasValue(before + 1);
        var pair = cache.getOrCreate(user);
        verifyNoInteractions(pair.direct(), pair.analysis(), pair.enhanced());
    }

    @Test void firstAcceptedMessageInAnExistingConversationAlsoInitializes() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/sessions/10/messages").header("Authorization", "Bearer active")
                .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"poetry\"}")).andReturn();
        if (result.getRequest().isAsyncStarted()) result = mvc.perform(asyncDispatch(result)).andReturn();
        assertThat(result.getResponse().getContentAsString()).contains("conclusion");
        assertThat(factory.creations).hasValue(before + 1);
        verify(processing).accept(user, 10L, "poetry", null);
    }

    @Test void waitingContinuationInitializesBeforeLoadingItsContext() throws Exception {
        var waiting = new SessionProcessingService.Accepted(10, user.userId(), 20, 30, 1, "yes", null,
                LocalDateTime.now().plusMinutes(2), SessionStatus.CONFIRMING_REPAIR);
        when(processing.accept(eq(user), eq(10L), anyString(), isNull())).thenReturn(waiting);
        when(contexts.load(10L)).thenAnswer(invocation -> {
            assertThat(factory.creations).hasValue(before + 1);
            return null;
        });
        MvcResult result = mvc.perform(post("/api/v1/sessions/10/messages").header("Authorization", "Bearer active")
                .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"yes\"}")).andReturn();
        if (result.getRequest().isAsyncStarted()) result = mvc.perform(asyncDispatch(result)).andReturn();
        assertThat(result.getResponse().getContentAsString()).contains("CONTEXT_EXPIRED");
        verifyNoInteractions(classifier);
    }

    @Test void busyAndForbiddenMessagesDoNotCreateServices() throws Exception {
        for (ErrorCode code : List.of(ErrorCode.SESSION_BUSY, ErrorCode.FORBIDDEN)) {
            when(processing.create(eq(user), anyString())).thenThrow(new ApiException(code, "Rejected"));
            assertThat(create()).contains(code.name());
        }
        assertThat(factory.creations).hasValue(before);
        verifyNoInteractions(leases, classifier);
    }

    @Test void getAndUnauthenticatedRequestsDoNotCreateAndRevocationWinsOverCachedInstance() throws Exception {
        mvc.perform(get("/api/v1/sessions").header("Authorization", "Bearer active")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/sessions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"problem\":\"poetry\"}")).andExpect(status().isUnauthorized());
        assertThat(factory.creations).hasValue(before);
        cache.getOrCreate(user);
        when(tokens.resolve("Bearer active")).thenReturn(null); // revoked or disabled user
        mvc.perform(post("/api/v1/sessions").header("Authorization", "Bearer active")
                .contentType(MediaType.APPLICATION_JSON).content("{\"problem\":\"poetry\"}"))
                .andExpect(status().isUnauthorized());
        verify(processing, never()).create(any(), anyString());
        assertThat(factory.creations).hasValue(before + 1);
    }

    @Test void failedLocalInitializationClosesAcceptedRequestAndLaterRequestCanRetry() throws Exception {
        factory.fail = true;
        assertThat(create()).contains("INTERNAL_ERROR");
        verify(processing).finish(eq(accepted), eq(SessionStatus.FAILED_REQUEST), anyString(), isNull(), eq(ErrorCode.INTERNAL_ERROR));
        verify(contexts).evict(10L);
        verify(leases).release(any());
        verifyNoInteractions(classifier);
        factory.fail = false;
        assertThat(create()).contains("conclusion");
        assertThat(factory.creations).hasValue(before + 2);
    }
}
