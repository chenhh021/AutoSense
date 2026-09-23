package com.chh.autosense.integration;

import com.chh.autosense.core.security.AuthUser;
import com.chh.autosense.core.session.ConversationQueryService;
import com.chh.autosense.domain.entity.*;
import com.chh.autosense.mapper.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.*;

class ConversationHistoryIT extends AbstractWorkflowIT {
    @Autowired ConversationQueryService conversations;
    @Autowired RepairSessionMapper sessions;
    @Autowired ChatMessageMapper messages;
    @Test void legacyConversationRemainsReadableWithoutWorkflowAndOwnedDeletionCleansBothKinds() throws Exception {
        var user = new AuthUser(1L);
        var session = new RepairSession(); session.setUserId(1L); session.setStatus("COMPLETED_ANSWERED");
        session.setConclusion("Legacy answer"); session.setConclusionType("ANSWERED"); sessions.insert(session);
        var message = new ChatMessage(); message.setSessionId(session.getId()); message.setRole("ASSISTANT"); message.setContent("Legacy answer"); messages.insert(message);
        var view = conversations.get(user, session.getId());
        assertThat(view.workflow()).isNull(); assertThat(view.conclusion().summary()).isEqualTo("Legacy answer");
        assertThatThrownBy(() -> conversations.get(new AuthUser(2L), session.getId())).isInstanceOf(com.chh.autosense.exception.ApiException.class);
        conversations.delete(user, session.getId()); assertThat(sessions.selectOneById(session.getId())).isNull();
        var accepted = start("什么是色温");
        conversations.delete(user, accepted.sessionId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workflow_checkpoint WHERE thread_id=?", Integer.class, accepted.requestId())).isZero();
        assertThat(workflows.selectOneById(accepted.requestId())).isNull();
    }
    @Test void historyReadDoesNotExecuteAndIdleWaitingWorkflowCanBeDeleted() throws Exception {
        var accepted = start("查询状态");
        long version = workflows.selectOneById(accepted.requestId()).getVersion();
        conversations.get(new AuthUser(1L), accepted.sessionId()); conversations.workflow(new AuthUser(1L), accepted.sessionId(), accepted.requestId());
        assertThat(workflows.selectOneById(accepted.requestId()).getVersion()).isEqualTo(version);
        conversations.delete(new AuthUser(1L), accepted.sessionId());
        assertThat(sessions.selectOneById(accepted.sessionId())).isNull();
        assertThat(workflows.selectOneById(accepted.requestId())).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workflow_approval WHERE request_id=?", Integer.class, accepted.requestId())).isZero();
    }

    @Test void dispatchingConversationWithExpiredClaimCanBeDeletedAndLateWorkerLosesFence() {
        var user = new AuthUser(1L);
        var accepted = persistence.admit(user, null, "读取设定温度");
        var claim = claims.acquire(accepted.requestId(), 1L, workflows.selectOneById(accepted.requestId()).getVersion());
        assertThat(sessions.selectOneById(accepted.sessionId()).getStatus()).isEqualTo("DISPATCHING");
        jdbc.update("UPDATE workflow_execution SET lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE request_id=?", accepted.requestId());
        conversations.delete(user, accepted.sessionId());
        assertThat(sessions.selectOneById(accepted.sessionId())).isNull();
        assertThat(workflows.selectOneById(accepted.requestId())).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_message WHERE session_id=?", Integer.class, accepted.sessionId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM repair_action_log WHERE session_id=?", Integer.class, accepted.sessionId())).isZero();
        assertThatThrownBy(() -> claims.requireFence(claim.requestId(), claim.fence()))
                .isInstanceOf(com.chh.autosense.exception.ApiException.class).hasMessage("WORKFLOW_CLAIM_LOST");
    }

    @Test void activeClaimBlocksDeletionButReleasedClaimDoesNot() {
        var accepted = persistence.admit(new AuthUser(1L), null, "查询温度");
        var claim = claims.acquire(accepted.requestId(), 1L, workflows.selectOneById(accepted.requestId()).getVersion());
        assertThatThrownBy(() -> conversations.delete(new AuthUser(1L), accepted.sessionId()))
                .isInstanceOf(com.chh.autosense.exception.ApiException.class).hasMessageContaining("仍在执行");
        assertThat(workflows.selectOneById(accepted.requestId())).isNotNull();
        assertThat(messages.selectOneById(accepted.messageId())).isNotNull();
        claims.release(claim);
        conversations.delete(new AuthUser(1L), accepted.sessionId());
        assertThat(sessions.selectOneById(accepted.sessionId())).isNull();
    }

    @Test void restartSuspendedWorkflowUsesCurrentListStatusAndIsDeletableWithoutResuming() {
        var accepted = persistence.admit(new AuthUser(1L), null, "查询温度");
        jdbc.update("UPDATE workflow_execution SET status='WAITING_RESUME' WHERE request_id=?", accepted.requestId());
        assertThat(conversations.list(new AuthUser(1L))).filteredOn(v -> v.sessionId() == accepted.sessionId())
                .singleElement().satisfies(v -> assertThat(v.status()).isEqualTo("CLARIFYING"));
        assertThatThrownBy(() -> conversations.delete(new AuthUser(2L), accepted.sessionId()))
                .isInstanceOf(com.chh.autosense.exception.ApiException.class).hasMessageContaining("无权");
        conversations.delete(new AuthUser(1L), accepted.sessionId());
        assertThat(workflows.selectOneById(accepted.requestId())).isNull();
    }

    @Test void uncertainCommandsStillBlockDeletionEvenWithoutAnActiveClaim() throws Exception {
        for (String uncertain : java.util.List.of("UNKNOWN", "IN_FLIGHT")) {
            var accepted = start("打开灯");
            decide(accepted, true);
            jdbc.update("UPDATE command_execution SET status=?,certainty=? WHERE request_id=?", uncertain, uncertain, accepted.requestId());
            assertThatThrownBy(() -> conversations.delete(new AuthUser(1L), accepted.sessionId()))
                    .isInstanceOf(com.chh.autosense.exception.ApiException.class).hasMessageContaining("结果尚未确定");
            assertThat(workflows.selectOneById(accepted.requestId())).isNotNull();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM command_execution WHERE request_id=?", Integer.class, accepted.requestId())).isEqualTo(1);
        }
    }
}
