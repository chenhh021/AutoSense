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
    @Test void historyReadDoesNotExecuteAndNonterminalWorkflowCannotBeDeleted() throws Exception {
        var accepted = start("查询状态");
        long version = workflows.selectOneById(accepted.requestId()).getVersion();
        conversations.get(new AuthUser(1L), accepted.sessionId()); conversations.workflow(new AuthUser(1L), accepted.sessionId(), accepted.requestId());
        assertThat(workflows.selectOneById(accepted.requestId()).getVersion()).isEqualTo(version);
        assertThatThrownBy(() -> conversations.delete(new AuthUser(1L), accepted.sessionId())).isInstanceOf(com.chh.autosense.exception.ApiException.class);
    }
}
