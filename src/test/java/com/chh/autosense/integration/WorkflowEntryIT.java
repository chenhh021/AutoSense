package com.chh.autosense.integration;

import com.chh.autosense.core.security.AuthUser;
import com.chh.autosense.core.session.*;
import com.chh.autosense.domain.dto.*;
import com.chh.autosense.domain.message.WorkflowEvent;
import com.chh.autosense.domain.enums.WorkflowStatus;
import com.chh.autosense.graph.WorkflowEventStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class WorkflowEntryIT extends AbstractWorkflowIT {
    @Autowired WorkflowExecutionService executions;
    @Autowired ConversationQueryService conversations;
    @Autowired WorkflowRecoveryService recovery;
    private final AuthUser user = new AuthUser(1L);

    @Test void expiredConfirmationProducesANewApprovalWithoutQueryingDevice() throws Exception {
        var last = consume(executions.create(user, null, "查询状态")).getLast();
        long session = last.data().conversationId(); String id = last.data().requestId();
        var view = conversations.workflow(user, session, id); String oldId = view.approval().approvalId();
        jdbc.update("UPDATE workflow_approval SET expires_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE approval_id=?", oldId);
        consume(executions.approval(user, session, id, new WorkflowApprovalRequest(view.approval().stepId(), oldId, true, view.version())));
        view = conversations.workflow(user, session, id);
        assertThat(view.status()).isEqualTo(WorkflowStatus.WAITING_APPROVAL);
        assertThat(view.approval().approvalId()).isNotEqualTo(oldId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM repair_action_log WHERE request_id=? AND event_type='QUERY_ATTEMPT_STARTED'", Integer.class, id)).isZero();
    }

    @Test void admittedCheckpointExecutesAndNextQuestionCreatesIndependentWorkflow() throws Exception {
        var first = consume(executions.create(user, null, "什么是色温"));
        var last = first.getLast();
        assertThat(last.type()).isEqualTo("CONCLUSION");
        long session = last.data().conversationId();
        var next = consume(executions.message(user, session, new MessageRequest("什么是亮度", null)));
        assertThat(next.getLast().data().requestId()).isNotEqualTo(last.data().requestId());
        assertThat(conversations.messages(user, session).stream().filter(m -> m.getRole().equals("USER"))).hasSize(2);
    }

    @Test void approvalContinuationAndCancellationPreserveCompletedQuery() throws Exception {
        var events = consume(executions.create(user, null, "查询亮度，如果低于30调到80"));
        var last = events.getLast(); long session = last.data().conversationId(); String id = last.data().requestId();
        var view = conversations.workflow(user, session, id);
        assertThat(view.status()).isEqualTo(WorkflowStatus.WAITING_APPROVAL);
        consume(executions.approval(user, session, id, new WorkflowApprovalRequest(view.approval().stepId(), view.approval().approvalId(), true, view.version())));
        view = conversations.workflow(user, session, id);
        assertThat(view.currentStep()).isEqualTo(1);
        consume(executions.cancel(user, session, id, view.version()));
        view = conversations.workflow(user, session, id);
        assertThat(view.status()).isEqualTo(WorkflowStatus.CANCELLED);
        assertThat(view.steps().getFirst().result()).containsEntry("brightness", 20);
        assertThat(view.steps().get(1).status().name()).isEqualTo("NOT_EXECUTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM command_execution WHERE request_id=?", Integer.class, id)).isZero();
        assertThat(consume(executions.cancel(user, session, id, view.version())).getLast().data().status()).isEqualTo(WorkflowStatus.CANCELLED);
    }

    @Test void restartedApprovalOnlyPersistsDecisionUntilExplicitResume() throws Exception {
        var last = consume(executions.create(user, null, "查询状态")).getLast();
        long session = last.data().conversationId(); String id = last.data().requestId();
        recovery.suspendAfterRestart();
        var view = conversations.workflow(user, session, id);
        var ack = consume(executions.approval(user, session, id, new WorkflowApprovalRequest(view.approval().stepId(), view.approval().approvalId(), true, view.version())));
        assertThat(ack.getLast().data().status()).isEqualTo(WorkflowStatus.WAITING_RESUME);
        view = conversations.workflow(user, session, id);
        assertThat(consume(executions.resume(user, session, id, view.version())).getLast().data().status()).isEqualTo(WorkflowStatus.COMPLETED);
    }

    private List<WorkflowEvent> consume(WorkflowExecutionService.Run run) {
        var events = new ArrayList<WorkflowEvent>();
        try (run) { new WorkflowEventStream().consume(run.stream(), events::add); }
        return events;
    }
}
