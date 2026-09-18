package com.chh.autosense.integration;

import com.chh.autosense.core.security.AuthUser;
import com.chh.autosense.core.session.*;
import com.chh.autosense.domain.dto.MessageRequest;
import com.chh.autosense.domain.message.WorkflowEvent;
import com.chh.autosense.graph.WorkflowEventStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Real graph and AI Service proxies; only model transport is the explicit local mock model. */
@TestPropertySource(properties = {"autosense.graph.mode=real", "autosense.knowledge.retrieval.min-score=0"})
class KnowledgeGraphIT extends AbstractIntegrationIT {
    @Autowired WorkflowExecutionService executions;
    @Autowired ConversationQueryService conversations;
    @Autowired JdbcTemplate jdbc;
    @Test void directAndEnhancedAnswersPersistThroughTheRealGraphAndSharedFactories() throws Exception {
        var direct = consume(executions.create(new AuthUser(1L), null, "什么是色温"));
        assertThat(direct).extracting(WorkflowEvent::type).contains("TEXT", "STEP_RESULT", "CONCLUSION");
        assertThat(direct).allMatch(e -> !e.code().startsWith("STUB_"));
        long session = direct.getLast().data().conversationId();
        var enhanced = consume(executions.message(new AuthUser(1L), session, new MessageRequest("MI-MJDPL01YL型号功能说明", null)));
        assertThat(enhanced.getLast().type()).isEqualTo("CONCLUSION");
        var result = enhanced.stream().filter(e -> e.type().equals("STEP_RESULT")).findFirst().orElseThrow();
        assertThat(result.data().payload().get("answer").toString()).contains("来源：", "MI-MJDPL01YL");
        var view = conversations.workflow(new AuthUser(1L), session, result.data().requestId());
        assertThat((List<?>) view.steps().getFirst().result().get("sources")).isNotEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM repair_action_log WHERE request_id=? AND params LIKE '%TEXT_RESET%'", Integer.class, result.data().requestId())).isZero();
        assertThat(conversations.messages(new AuthUser(1L), session)).filteredOn(m -> m.getRole().equals("USER")).hasSize(2);
    }
    private List<WorkflowEvent> consume(WorkflowExecutionService.Run run) {
        var events = new ArrayList<WorkflowEvent>();
        try (run) { new WorkflowEventStream().consume(run.stream(), events::add); }
        return events;
    }
}
