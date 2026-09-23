package com.chh.autosense.integration;

import com.chh.autosense.core.security.AuthUser;
import com.chh.autosense.domain.enums.WorkflowStatus;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class WorkflowApprovalIT extends AbstractWorkflowIT {
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean(name = "workflowBusinessActions")
    com.chh.autosense.graph.node.WorkflowStepActions businessActions;

    @org.springframework.beans.factory.annotation.Autowired com.chh.autosense.core.session.WorkflowExecutionService executions;
    @org.springframework.beans.factory.annotation.Autowired com.chh.autosense.core.session.ConversationQueryService conversations;

    @Test void cancellationAndApprovalRaceCannotExecuteAnUnclaimedDeviceOperation() throws Exception {
        var accepted = start("查询状态"); var a = state(accepted).control().approvalRef();
        long version = workflows.selectOneById(accepted.requestId()).getVersion();
        var go = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var approval = pool.submit(() -> {
                go.await();
                try { approvals.decide(new AuthUser(1L), accepted.sessionId(), accepted.requestId(), a.stepId(), a.approvalId(), true, version); }
                catch (com.chh.autosense.exception.ApiException expectedConflict) { }
                return true;
            });
            var cancellation = pool.submit(() -> {
                go.await();
                try (var run = executions.cancel(new AuthUser(1L), accepted.sessionId(), accepted.requestId(), version)) {
                    for (var ignored : run.stream()) { }
                } catch (com.chh.autosense.exception.ApiException expectedConflict) { }
                return true;
            });
            go.countDown();
            approval.get(20, java.util.concurrent.TimeUnit.SECONDS); cancellation.get(20, java.util.concurrent.TimeUnit.SECONDS);
        }
        var view = conversations.workflow(new AuthUser(1L), accepted.sessionId(), accepted.requestId());
        if (!view.status().terminal()) {
            try (var run = executions.cancel(new AuthUser(1L), accepted.sessionId(), accepted.requestId(), view.version())) {
                for (var ignored : run.stream()) { }
            }
        }
        assertThat(conversations.workflow(new AuthUser(1L), accepted.sessionId(), accepted.requestId()).status()).isEqualTo(WorkflowStatus.CANCELLED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM repair_action_log WHERE request_id=? AND event_type='QUERY_ATTEMPT_STARTED'", Integer.class, accepted.requestId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM command_execution WHERE request_id=?", Integer.class, accepted.requestId())).isZero();
    }

    @Test void concurrentIdenticalDecisionsGrantOnlyOneContinuation() throws Exception {
        var accepted = start("查询状态"); var a = state(accepted).control().approvalRef();
        long version = workflows.selectOneById(accepted.requestId()).getVersion();
        var ready = new java.util.concurrent.CountDownLatch(2);
        var go = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.Callable<Boolean> decision = () -> {
                ready.countDown(); go.await();
                return approvals.decide(new AuthUser(1L), accepted.sessionId(), accepted.requestId(), a.stepId(), a.approvalId(), true, version).execute();
            };
            var first = pool.submit(decision); var second = pool.submit(decision);
            assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); go.countDown();
            assertThat(java.util.List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM repair_action_log WHERE request_id=? AND event_type='APPROVAL_DECIDED'", Integer.class, accepted.requestId())).isEqualTo(1);
    }

    @Test void changedParametersCannotReuseAValidApproval() throws Exception {
        var accepted = start("打开灯"); var state = state(accepted); var a = state.control().approvalRef();
        var decision = approvals.decide(new AuthUser(1L), accepted.sessionId(), accepted.requestId(), a.stepId(), a.approvalId(), true,
                workflows.selectOneById(accepted.requestId()).getVersion());
        var claim = claims.acquire(accepted.requestId(), 1, decision.version());
        try {
            var control = state.control(); var command = new java.util.LinkedHashMap<>(control.command()); command.put("power", false);
            var changed = com.chh.autosense.graph.node.GraphUpdates.apply(state, java.util.Map.of(
                    com.chh.autosense.graph.state.AssistantState.WORKFLOW, fenced(state.workflow(), claim.fence()),
                    com.chh.autosense.graph.state.AssistantState.CONTROL, new com.chh.autosense.graph.state.AssistantState.ControlContext(command,
                            control.commandExecutionId(), true, control.risk(), decision.approval(), control.idempotencyKey(), java.util.Map.of())));
            assertThatThrownBy(() -> approvals.requireApproved(changed)).isInstanceOf(com.chh.autosense.exception.ApiException.class);
        } finally { claims.release(claim); }
    }
    @Test void eachDeviceStepHasItsOwnDurableDecisionAndQueryNeverCreatesACommand() throws Exception {
        // Test independent approvals, without using simulated evidence to decide a control.
        var steps = java.util.List.of(
                com.chh.autosense.graph.node.StubWorkflowActions.step("s1", com.chh.autosense.domain.enums.PlanStepType.DEVICE_QUERY),
                com.chh.autosense.graph.node.StubWorkflowActions.step("s2", com.chh.autosense.domain.enums.PlanStepType.DEVICE_CONTROL));
        org.mockito.Mockito.doReturn(new com.chh.autosense.graph.node.WorkflowStepActions.PlanProposal("PLAN", steps, null, com.chh.autosense.graph.node.StubWorkflowActions.fixtureDevices()))
                .when(businessActions).plan(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        var accepted = start("查询亮度，低于30就调到80");
        var query = state(accepted).control().approvalRef();
        assertThat(state(accepted).workflow().status()).isEqualTo(WorkflowStatus.WAITING_APPROVAL);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM repair_action_log WHERE request_id=? AND event_type='QUERY_ATTEMPT_STARTED'", Integer.class, accepted.requestId())).isZero();
        decide(accepted, true);
        var control = state(accepted).control().approvalRef();
        assertThat(control.stepId()).isNotEqualTo(query.stepId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM command_execution WHERE request_id=?", Integer.class, accepted.requestId())).isZero();
        var duplicate = approvals.decide(new AuthUser(1L), accepted.sessionId(), accepted.requestId(), query.stepId(), query.approvalId(), true, 0);
        assertThat(duplicate.execute()).isFalse();
        decide(accepted, false);
        assertThat(state(accepted).workflow().status()).isEqualTo(WorkflowStatus.FAILED);
        assertThat(state(accepted).plan().results().get(query.stepId()).successful()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM command_execution WHERE request_id=?", Integer.class, accepted.requestId())).isZero();
    }

    @Test void anotherUserCannotDecideOrAcquireTheWorkflow() throws Exception {
        var accepted = start("查询状态"); var a = state(accepted).control().approvalRef();
        long version = workflows.selectOneById(accepted.requestId()).getVersion();
        assertThatThrownBy(() -> approvals.decide(new AuthUser(2L), accepted.sessionId(), accepted.requestId(), a.stepId(), a.approvalId(), true, version))
                .isInstanceOf(com.chh.autosense.exception.ApiException.class);
        assertThatThrownBy(() -> claims.acquire(accepted.requestId(), 2, version)).isInstanceOf(com.chh.autosense.exception.ApiException.class);
        assertThat(state(accepted).control().approvalRef().status()).isEqualTo("PENDING");
    }
}
