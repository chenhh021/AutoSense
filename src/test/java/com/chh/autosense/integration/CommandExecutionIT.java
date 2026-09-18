package com.chh.autosense.integration;

import com.chh.autosense.domain.enums.WorkflowStatus;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CommandExecutionIT extends AbstractWorkflowIT {
    @org.springframework.beans.factory.annotation.Autowired com.chh.autosense.core.session.CommandExecutionService commands;

    @Test void timeoutSettlementRejectsLateSuccessAndNeverReopensUnknownWrite() throws Exception {
        var accepted = start("打开灯"); var saved = state(accepted); var a = saved.control().approvalRef();
        var decision = approvals.decide(new com.chh.autosense.core.security.AuthUser(1L), accepted.sessionId(), accepted.requestId(), a.stepId(), a.approvalId(), true,
                workflows.selectOneById(accepted.requestId()).getVersion());
        var claim = claims.acquire(accepted.requestId(), 1, decision.version());
        try {
            var c = saved.control();
            var state = com.chh.autosense.graph.node.GraphUpdates.apply(saved, java.util.Map.of(
                    com.chh.autosense.graph.state.AssistantState.WORKFLOW, fenced(saved.workflow(), claim.fence()),
                    com.chh.autosense.graph.state.AssistantState.CONTROL, new com.chh.autosense.graph.state.AssistantState.ControlContext(c.command(),
                            java.util.UUID.randomUUID().toString(), true, c.risk(), decision.approval(), c.idempotencyKey(), java.util.Map.of())));
            commands.prepare(state); commands.prepare(state);
            String attempt = commands.begin(state);
            var failure = com.chh.autosense.graph.node.GraphUpdates.failure(state, "DEVICE_RESULT_UNKNOWN", com.chh.autosense.graph.state.ExecutionPlan.Certainty.UNKNOWN);
            commands.reconcileAttempt(state, failure);
            assertThatThrownBy(() -> commands.finish(state, attempt, java.util.Map.of("power", true), "", com.chh.autosense.graph.state.ExecutionPlan.Certainty.SUCCEEDED, false))
                    .isInstanceOf(com.chh.autosense.exception.ApiException.class);
            assertThatThrownBy(() -> commands.begin(state)).isInstanceOf(com.chh.autosense.graph.node.StepFailure.class);
            var row = jdbc.queryForMap("SELECT status,certainty,attempt_count FROM command_execution WHERE request_id=?", accepted.requestId());
            assertThat(row).containsEntry("status", "UNKNOWN").containsEntry("certainty", "UNKNOWN");
            assertThat(((Number) row.get("attempt_count")).intValue()).isEqualTo(1);
        } finally { claims.release(claim); }
    }
    @Test void commandIntentAttemptAndOutcomeShareOneIdentityAndPreserveAuditOrder() throws Exception {
        var accepted = start("打开灯"); decide(accepted, true);
        assertThat(state(accepted).workflow().status()).isEqualTo(WorkflowStatus.COMPLETED);
        var command = jdbc.queryForMap("SELECT * FROM command_execution WHERE request_id=?", accepted.requestId());
        assertThat(command.get("status")).isEqualTo("SUCCEEDED");
        assertThat(((Number) command.get("attempt_count")).intValue()).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT event_type FROM repair_action_log WHERE request_id=? AND command_execution_id=? ORDER BY event_sequence",
                String.class, accepted.requestId(), command.get("command_id")))
                .containsExactly("COMMAND_PREPARED", "COMMAND_ATTEMPT_STARTED", "COMMAND_ATTEMPT_RESULT");
        assertThat(jdbc.queryForObject("SELECT certainty FROM workflow_step WHERE request_id=?", String.class, accepted.requestId())).isEqualTo("SUCCEEDED");
        assertThatThrownBy(() -> claims.acquire(accepted.requestId(), 1, workflows.selectOneById(accepted.requestId()).getVersion()))
                .isInstanceOf(com.chh.autosense.exception.ApiException.class);
    }
}
