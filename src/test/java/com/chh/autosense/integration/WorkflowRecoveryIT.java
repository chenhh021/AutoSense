package com.chh.autosense.integration;

import com.chh.autosense.core.security.AuthUser;
import com.chh.autosense.core.session.*;
import com.chh.autosense.domain.enums.WorkflowStatus;
import com.chh.autosense.graph.MainGraphFactory;
import com.chh.autosense.graph.node.*;
import com.chh.autosense.graph.state.AssistantState;
import org.bsc.langgraph4j.*;
import org.bsc.langgraph4j.checkpoint.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;

class WorkflowRecoveryIT extends AbstractWorkflowIT {
    @Autowired WorkflowRecoveryService recovery;
    @Autowired MainGraphFactory factory;
    @Autowired BaseCheckpointSaver saver;
    @Autowired @Qualifier("workflowBusinessActions") WorkflowStepActions business;

    @Test void restartRequiresExplicitResumeEvenAfterAValidApproval() throws Exception {
        var accepted = start("查询状态"); recovery.suspendAfterRestart();
        var a = state(accepted).control().approvalRef();
        var decision = approvals.decide(new AuthUser(1L), accepted.sessionId(), accepted.requestId(), a.stepId(), a.approvalId(), true,
                workflows.selectOneById(accepted.requestId()).getVersion());
        assertThat(decision.execute()).isFalse();
        assertThat(workflows.selectOneById(accepted.requestId()).getStatus()).isEqualTo("WAITING_RESUME");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM repair_action_log WHERE request_id=? AND event_type='QUERY_ATTEMPT_STARTED'", Integer.class, accepted.requestId())).isZero();
        resume(accepted);
        assertThat(state(accepted).workflow().status()).isEqualTo(WorkflowStatus.COMPLETED);
    }

    @Test void resumingPendingApprovalDoesNotDuplicateItsMessage() throws Exception {
        var accepted = start("查询状态");
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM chat_message WHERE workflow_request_id=? AND role='ASSISTANT'", Integer.class, accepted.requestId());
        recovery.suspendAfterRestart();
        resume(accepted);
        assertThat(state(accepted).workflow().status()).isEqualTo(WorkflowStatus.WAITING_APPROVAL);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_message WHERE workflow_request_id=? AND role='ASSISTANT'", Integer.class, accepted.requestId())).isEqualTo(before);
    }

    @Test void committedExpensiveResultIsReusedWhenTheFollowingCheckpointFails() throws Exception {
        var accepted = persistence.admit(new AuthUser(1L), null, "什么是色温");
        var claim = claims.acquire(accepted.requestId(), 1, workflows.selectOneById(accepted.requestId()).getVersion());
        var failed = new AtomicBoolean();
        BaseCheckpointSaver faulty = new BaseCheckpointSaver() {
            public Collection<Checkpoint> list(RunnableConfig config) { return saver.list(config); }
            public Optional<Checkpoint> get(RunnableConfig config) { return saver.get(config); }
            public Tag release(RunnableConfig config) throws Exception { return saver.release(config); }
            public RunnableConfig put(RunnableConfig config, Checkpoint checkpoint) throws Exception {
                if (checkpoint.getNodeId().equals("KnowledgeConsult") && failed.compareAndSet(false, true)) throw new IllegalStateException("Injected checkpoint failure");
                return saver.put(config, checkpoint);
            }
        };
        var interrupted = factory.compile(faulty);
        var initial = new LinkedHashMap<>(AssistantState.initial(new AssistantState.RequestContext(accepted.requestId(), accepted.sessionId(), 1, accepted.text())));
        initial.put(AssistantState.WORKFLOW, fenced(new AssistantState(initial).workflow(), claim.fence()));
        try {
            assertThatThrownBy(() -> { for (var ignored : interrupted.stream(GraphInput.args(initial), config(claim))) { } }).isInstanceOf(RuntimeException.class);
            recovery.suspendFailedExecution(claim);
        } finally { claims.release(claim); }
        assertThat(jdbc.queryForObject("SELECT status FROM workflow_step WHERE request_id=?", String.class, accepted.requestId())).isEqualTo("COMPLETED");
        int answers = Collections.frequency(((StubWorkflowActions) business).calls(), "answer");
        resume(accepted);
        assertThat(state(accepted).workflow().status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(Collections.frequency(((StubWorkflowActions) business).calls(), "answer")).isEqualTo(answers);
    }

    private void resume(AcceptedWorkflow accepted) throws Exception {
        var restored = recovery.restore(new AuthUser(1L), accepted.sessionId(), accepted.requestId(),
                workflows.selectOneById(accepted.requestId()).getVersion(), true);
        try {
            var config = graph.updateState(config(restored.claim()), restored.state().data(), restored.asNode());
            for (var ignored : graph.stream(GraphInput.resume(), config)) { }
        } finally { claims.release(restored.claim()); }
    }
}
