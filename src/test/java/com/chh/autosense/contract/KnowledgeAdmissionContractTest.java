package com.chh.autosense.contract;

import com.chh.autosense.config.GraphProperties;
import com.chh.autosense.core.security.AuthUser;
import com.chh.autosense.core.session.*;
import com.chh.autosense.core.session.memory.*;
import com.chh.autosense.domain.dto.MessageRequest;
import com.chh.autosense.domain.entity.WorkflowExecution;
import com.chh.autosense.exception.*;
import com.chh.autosense.graph.state.AssistantState;
import com.chh.autosense.mapper.WorkflowExecutionMapper;
import com.chh.autosense.service.knowledge.UserAiServiceCache;
import org.bsc.langgraph4j.*;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.junit.jupiter.api.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KnowledgeAdmissionContractTest {
    final WorkflowPersistenceService persistence = mock(WorkflowPersistenceService.class);
    final WorkflowClaimService claims = mock(WorkflowClaimService.class);
    final WorkflowRecoveryService recovery = mock(WorkflowRecoveryService.class);
    final ConversationQueryService conversations = mock(ConversationQueryService.class);
    final ConversationHistoryService history = mock(ConversationHistoryService.class);
    final WorkflowExecutionMapper workflows = mock(WorkflowExecutionMapper.class);
    final BaseCheckpointSaver saver = mock(BaseCheckpointSaver.class);
    final UserAiServiceCache cache = mock(UserAiServiceCache.class);
    final CompiledGraph<AssistantState> graph = mock(CompiledGraph.class);
    final AuthUser user = new AuthUser(1L);
    final WorkflowClaimService.Claim claim = new WorkflowClaimService.Claim("request", "owner", 1);
    WorkflowExecutionService service;

    @BeforeEach void setup() {
        service = new WorkflowExecutionService(graph, saver, persistence, claims, mock(WorkflowApprovalService.class),
                recovery, conversations, history, workflows, mock(SessionLeaseService.class),
                new GraphProperties("real", 8, 30, null, 2, 1000, 300, 30, 300, 256), cache);
    }
    @AfterEach void close() { service.closeScheduler(); }

    @Test void rejectedAdmissionAndForeignConversationNeverInitializeServices() throws Exception {
        when(persistence.admit(user, null, "question")).thenThrow(WorkflowClaimService.conflict("WORKFLOW_BUSY"));
        assertThatThrownBy(() -> service.create(user, null, "question")).isInstanceOf(ApiException.class);
        when(conversations.owned(user, 2L)).thenThrow(new ApiException(ErrorCode.FORBIDDEN, "Forbidden"));
        assertThatThrownBy(() -> service.message(user, 2L, new MessageRequest("question", null, null, null)))
                .isInstanceOf(ApiException.class);
        verifyNoInteractions(cache, graph, saver, history);
    }

    @Test void cacheInitializationFollowsDurableAdmissionAndCheckpointAndReleasesClaimOnFailure() throws Exception {
        accepted();
        when(cache.getOrCreate(user)).thenThrow(new IllegalStateException("factory failure"));
        assertThatThrownBy(() -> service.create(user, null, "question")).hasMessage("factory failure");
        var order = inOrder(persistence, claims, saver, cache, recovery);
        order.verify(persistence).admit(user, null, "question");
        order.verify(claims).acquire("request", 1, 0);
        order.verify(saver).put(any(), any());
        order.verify(cache).getOrCreate(user);
        order.verify(recovery).suspendFailedExecution(claim);
        order.verify(claims).release(claim);
        verifyNoInteractions(graph);
    }

    @Test void failingSuspensionStillReleasesClaimAndPreservesOriginalFailure() throws Exception {
        accepted();
        when(cache.getOrCreate(user)).thenThrow(new IllegalStateException("factory failure"));
        doThrow(new IllegalStateException("checkpoint failure")).when(recovery).suspendFailedExecution(claim);
        assertThatThrownBy(() -> service.create(user, null, "question")).hasMessage("factory failure")
                .satisfies(error -> assertThat(error.getSuppressed()).hasSize(1));
        verify(claims).release(claim); verifyNoInteractions(graph);
    }

    private void accepted() throws Exception {
        when(persistence.admit(user, null, "question")).thenReturn(new AcceptedWorkflow("request", 1, 1, 2, 3, 1, "question"));
        var row = new WorkflowExecution(); row.setVersion(0L); row.setLastEventSequence(0L);
        when(workflows.selectOneById("request")).thenReturn(row);
        when(claims.acquire("request", 1, 0)).thenReturn(claim);
        when(history.snapshot(1, 1, 2)).thenReturn(new ConversationHistorySnapshot(1, 2, List.of()));
        when(saver.put(any(), any())).thenReturn(RunnableConfig.builder().threadId("request").build());
    }
}
