package com.chh.autosense.integration;

import com.chh.autosense.domain.enums.WorkflowStatus;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class WorkflowAuditIT extends AbstractWorkflowIT {
    @org.springframework.beans.factory.annotation.Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @org.springframework.beans.factory.annotation.Autowired com.chh.autosense.core.session.WorkflowAuditService audit;

    @Test void rollbackDoesNotPublishSuccessOrLeaveMessagesAuditAndActivePointer() {
        long sessions = jdbc.queryForObject("SELECT COUNT(*) FROM repair_session", Long.class);
        long messages = jdbc.queryForObject("SELECT COUNT(*) FROM chat_message", Long.class);
        long events = jdbc.queryForObject("SELECT COUNT(*) FROM repair_action_log", Long.class);
        try (var logs = new com.chh.autosense.support.LogCaptureSupport()) {
            assertThatThrownBy(() -> new org.springframework.transaction.support.TransactionTemplate(transactions)
                    .executeWithoutResult(transaction -> {
                        persistence.admit(new com.chh.autosense.core.security.AuthUser(1L), null, "private-rollback-input");
                        throw new IllegalStateException("rollback");
                    })).hasMessage("rollback");
            assertThat(logs.rendered()).doesNotContain("Workflow event committed", "private-rollback-input");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM repair_session", Long.class)).isEqualTo(sessions);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_message", Long.class)).isEqualTo(messages);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM repair_action_log", Long.class)).isEqualTo(events);
    }

    @Test void visibleBodiesLiveInMessagesAndInternalEventsUseReferences() throws Exception {
        var accepted = start("什么是色温");
        assertThat(state(accepted).workflow().status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_message WHERE workflow_request_id=? AND role='USER'", Integer.class, accepted.requestId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_message WHERE workflow_request_id=? AND role='ASSISTANT'", Integer.class, accepted.requestId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM repair_action_log WHERE request_id=? AND chat_message_ref IS NOT NULL", Integer.class, accepted.requestId())).isEqualTo(3);
        assertThat(jdbc.queryForList("SELECT params FROM repair_action_log WHERE request_id=?", String.class, accepted.requestId()))
                .allSatisfy(json -> assertThat(json).doesNotContain("什么是色温", "模拟知识回答"));
        assertThat(jdbc.queryForObject("SELECT active_workflow_request_id FROM repair_session WHERE id=?", String.class, accepted.sessionId())).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM command_execution WHERE request_id=?", Integer.class, accepted.requestId())).isZero();
    }
}
