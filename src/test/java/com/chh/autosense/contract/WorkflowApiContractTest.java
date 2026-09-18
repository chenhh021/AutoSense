package com.chh.autosense.contract;

import com.chh.autosense.config.GraphProperties;
import com.chh.autosense.controller.SessionController;
import com.chh.autosense.core.security.*;
import com.chh.autosense.core.session.*;
import com.chh.autosense.domain.enums.WorkflowStatus;
import com.chh.autosense.exception.*;
import com.chh.autosense.graph.node.GraphUpdates;
import com.chh.autosense.graph.state.AssistantState;
import org.bsc.langgraph4j.NodeOutput;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SessionController.class)
@Import({SecurityConfig.class, BearerTokenAuthFilter.class})
@EnableConfigurationProperties(GraphProperties.class)
class WorkflowApiContractTest {
    @Autowired MockMvc mvc;
    @MockitoBean WorkflowExecutionService executions;
    @MockitoBean ConversationQueryService conversations;
    @MockitoBean UserTokenResolver tokens;
    @MockitoBean(name = "applicationTaskExecutor") TaskExecutor executor;
    private static final String ROOT = "/api/v1/sessions/1/workflows/00000000-0000-0000-0000-000000000001";

    @BeforeEach void setup() {
        when(tokens.resolve("Bearer user-1")).thenReturn(new AuthUser(1L));
        doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; }).when(executor).execute(any());
    }
    @ParameterizedTest @ValueSource(strings = {"approval", "resume", "cancel"})
    void workflowMutationsRequireAuthentication(String operation) throws Exception {
        mvc.perform(post(ROOT + "/" + operation).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(executions);
    }
    @ParameterizedTest @ValueSource(strings = {"resume", "cancel"})
    void stateAndSecurityOverridesAreRejected(String operation) throws Exception {
        mvc.perform(post(ROOT + "/" + operation).header("Authorization", "Bearer user-1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":1,\"permission\":true,\"userId\":99}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(executions);
    }
    @Test void incompleteApprovalIsRejectedBeforeExecution() throws Exception {
        mvc.perform(post(ROOT + "/approval").header("Authorization", "Bearer user-1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"stepId\":\"s1\",\"approved\":true}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(executions);
    }
    @Test void versionConflictIsHttp409BeforeOpeningStream() throws Exception {
        when(executions.resume(any(), eq(1L), anyString(), eq(3L))).thenThrow(WorkflowClaimService.conflict("VERSION_CONFLICT"));
        mvc.perform(post(ROOT + "/resume").header("Authorization", "Bearer user-1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":3}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.data.code").value("VERSION_CONFLICT"));
    }
    @Test void graphSnapshotsProduceWorkflowBeforeLegacyAndStepResultDoesNotClose() throws Exception {
        var run = mock(WorkflowExecutionService.Run.class);
        var state = new AssistantState(AssistantState.initial(new AssistantState.RequestContext("00000000-0000-0000-0000-000000000001", 1, 1, "private input")));
        var result = GraphUpdates.apply(state, GraphUpdates.event(state, WorkflowStatus.RUNNING, "STEP_RESULT", "STEP_COMPLETED", "Query complete", Map.of("brightness", 20)));
        var confirm = GraphUpdates.apply(result, GraphUpdates.event(result, WorkflowStatus.WAITING_APPROVAL, "CONFIRM", "WAITING_APPROVAL", "Confirm control", Map.of("approvalId", "public-reference")));
        when(run.stream()).thenReturn(List.of(NodeOutput.of("Save", result), NodeOutput.of("CompleteStep", result), NodeOutput.of("PrepareApproval", confirm)));
        when(executions.create(any(), isNull(), anyString())).thenReturn(run);
        var response = mvc.perform(post("/api/v1/sessions").header("Authorization", "Bearer user-1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"problem\":\"Query and control\"}")).andReturn();
        String body = mvc.perform(asyncDispatch(response)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("event:workflow", "event:token", "event:awaiting").doesNotContain("private input", "executionFence", "messages", "event:conclusion");
        assertThat(body.indexOf("event:workflow")).isLessThan(body.indexOf("event:token"));
        assertThat(body.indexOf("event:token")).isLessThan(body.indexOf("event:awaiting"));
        assertThat(body.split("event:workflow", -1)).hasSize(3);
        verify(run).close();
    }
    @Test void interruptedIterationSuspendsAndNeverPublishesPendingApproval() throws Exception {
        var run = mock(WorkflowExecutionService.Run.class);
        when(run.stream()).thenReturn(() -> new Iterator<>() {
            public boolean hasNext() { throw new IllegalStateException("Injected checkpoint failure"); }
            public NodeOutput<AssistantState> next() { throw new NoSuchElementException(); }
        });
        when(executions.create(any(), isNull(), anyString())).thenReturn(run);
        var response = mvc.perform(post("/api/v1/sessions").header("Authorization", "Bearer user-1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"problem\":\"Query\"}")).andReturn();
        String body = mvc.perform(asyncDispatch(response)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("INTERNAL_ERROR").doesNotContain("event:awaiting", "Injected checkpoint failure");
        verify(run).failed(); verify(run).close();
    }
    @Test void readsDoNotInvokeExecution() throws Exception {
        mvc.perform(get("/api/v1/sessions").header("Authorization", "Bearer user-1")).andExpect(status().isOk());
        mvc.perform(get(ROOT).header("Authorization", "Bearer user-1")).andExpect(status().isOk());
        verifyNoInteractions(executions);
    }

    @Test void invalidOrUnauthenticatedCreationNeverStartsAWorkflow() throws Exception {
        mvc.perform(post("/api/v1/sessions").contentType(MediaType.APPLICATION_JSON).content("{\"problem\":\"hello\"}"))
                .andExpect(status().isUnauthorized());
        for (String body : List.of("{}", "{\"problem\":\" \"}")) {
            mvc.perform(post("/api/v1/sessions").header("Authorization", "Bearer user-1")
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(executions);
    }

    @Test void deletingConversationUsesOwnedServiceAndPreservesHttpFailures() throws Exception {
        mvc.perform(delete("/api/v1/sessions/1")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/sessions/1").header("Authorization", "Bearer user-1")).andExpect(status().isNoContent());
        for (var code : List.of(ErrorCode.FORBIDDEN, ErrorCode.SESSION_NOT_FOUND, ErrorCode.WORKFLOW_BUSY)) {
            doThrow(new ApiException(code, "controlled failure")).when(conversations).delete(any(), eq(2L));
            int status = code == ErrorCode.FORBIDDEN ? 403 : code == ErrorCode.SESSION_NOT_FOUND ? 404 : 409;
            mvc.perform(delete("/api/v1/sessions/2").header("Authorization", "Bearer user-1"))
                    .andExpect(status().is(status));
        }
        verifyNoInteractions(executions);
    }

    @Test void rejectedTaskSubmissionClosesResourcesEvenIfSuspensionFails() throws Exception {
        var run = mock(WorkflowExecutionService.Run.class);
        when(executions.create(any(), isNull(), anyString())).thenReturn(run);
        doThrow(new java.util.concurrent.RejectedExecutionException("private-executor-detail")).when(executor).execute(any());
        doThrow(new IllegalStateException("private-checkpoint-detail")).when(run).failed();
        var response = mvc.perform(post("/api/v1/sessions").header("Authorization", "Bearer user-1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"problem\":\"question\"}")).andReturn();
        String body = mvc.perform(asyncDispatch(response)).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("INTERNAL_ERROR").doesNotContain("private-", "event:conclusion");
        verify(run).failed(); verify(run).close();
    }
}
