package com.chh.autosense.graph;

import com.chh.autosense.config.GraphProperties;
import com.chh.autosense.graph.MainGraphFactory;
import com.chh.autosense.graph.node.StubWorkflowActions;
import com.chh.autosense.graph.state.AssistantState;
import com.chh.autosense.graph.state.ExecutionPlan;
import com.chh.autosense.graph.node.WorkflowStepActions.PlanProposal;
import com.chh.autosense.domain.enums.*;
import org.bsc.langgraph4j.*;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class MainGraphStubTest {
    private static final GraphProperties PROPERTIES = new GraphProperties("stub", 8, 30, null, 2, 0, 300, 30, 300, 256);

    @Test void allFourSimpleIntentsUseTheirOwnCapabilities() throws Exception {
        for (var entry : Map.of("什么是色温", "answer", "查询设备状态", "query", "灯泡不亮", "diagnose", "打开灯", "control").entrySet()) {
            var actions = new StubWorkflowActions();
            var run = new Run(actions, entry.getKey());
            if (run.state().workflow().status() == WorkflowStatus.WAITING_APPROVAL) run.approve("APPROVED");
            assertThat(run.state().workflow().status()).isEqualTo(WorkflowStatus.COMPLETED);
            assertThat(actions.calls().stream().filter(Set.of("answer", "query", "diagnose", "control")::contains).toList())
                    .containsExactly(entry.getValue());
            Object answer = run.state().plan().results().get("s1").data().get("answer");
            if (answer != null) assertThat(run.state().output().message()).isEqualTo(answer);
        }
    }

    @Test void untrustedStubEvidenceCannotDecideToSkipAControl() throws Exception {
        var actions = new StubWorkflowActions(null, Map.of(), 60, true);
        var run = new Run(actions, "查询亮度，低于30就调到80"); run.approve("APPROVED");
        assertThat(run.state().workflow().status()).isEqualTo(WorkflowStatus.FAILED);
        assertThat(run.state().workflow().failureCode()).isEqualTo("MOCK_EVIDENCE_NOT_ALLOWED");
        assertThat(actions.calls()).doesNotContain("control", "prepareCommand");
    }

    @Test void diagnosisAndVerificationHaveNoHiddenDeviceCalls() throws Exception {
        var steps = List.of(StubWorkflowActions.step("s1", PlanStepType.DEVICE_QUERY),
                StubWorkflowActions.step("s2", PlanStepType.FAULT_DIAGNOSIS),
                StubWorkflowActions.step("s3", PlanStepType.DEVICE_CONTROL),
                StubWorkflowActions.step("s4", PlanStepType.DEVICE_QUERY));
        var actions = new StubWorkflowActions(new PlanProposal("PLAN", steps, null), Map.of(), 20, true);
        var run = new Run(actions, "fixture");
        assertThat(actions.calls()).containsExactly("plan");
        run.approve("APPROVED");
        assertThat(actions.calls()).containsExactly("plan", "query", "diagnose");
        run.approve("APPROVED");
        assertThat(actions.calls()).containsExactly("plan", "query", "diagnose", "prepareCommand", "control");
        run.approve("REJECTED");
        assertThat(run.state().workflow().status()).isEqualTo(WorkflowStatus.FAILED);
        assertThat(run.state().plan().results().get("s3").successful()).isTrue();
        assertThat(actions.commandCount()).isEqualTo(1);
        assertThat(Collections.frequency(actions.calls(), "query")).isEqualTo(1);
    }

    @Test void consecutiveQueriesEachWaitAndRefusalStopsRemainingSteps() throws Exception {
        var steps = List.of(StubWorkflowActions.step("s1", PlanStepType.DEVICE_QUERY),
                StubWorkflowActions.step("s2", PlanStepType.DEVICE_QUERY), StubWorkflowActions.step("s3", PlanStepType.KNOWLEDGE_CONSULT));
        var actions = new StubWorkflowActions(new PlanProposal("PLAN", steps, null), Map.of(), 20, true);
        var run = new Run(actions, "fixture"); run.approve("APPROVED");
        assertThat(Collections.frequency(actions.calls(), "query")).isEqualTo(1);
        run.approve("REJECTED");
        assertThat(run.state().plan().results().get("s3").status()).isEqualTo(ExecutionPlan.StepStatus.NOT_EXECUTED);
        assertThat(actions.calls()).doesNotContain("answer");
    }

    @Test void onlyTimeoutsRetryAndUnsafeUnknownWritesNeverResend() throws Exception {
        for (var fault : List.of(StubWorkflowActions.Fault.TIMEOUT_ONCE, StubWorkflowActions.Fault.TIMEOUT_ALWAYS,
                StubWorkflowActions.Fault.EXPLICIT_ERROR, StubWorkflowActions.Fault.REFUSED)) {
            var actions = new StubWorkflowActions(null, Map.of("s1", fault), 20, true);
            var run = new Run(actions, "什么是色温");
            int expected = fault == StubWorkflowActions.Fault.TIMEOUT_ONCE ? 2 : fault == StubWorkflowActions.Fault.TIMEOUT_ALWAYS ? 3 : 1;
            assertThat(Collections.frequency(actions.calls(), "answer")).isEqualTo(expected);
            assertThat(run.state().workflow().status()).isEqualTo(fault == StubWorkflowActions.Fault.TIMEOUT_ONCE
                    ? WorkflowStatus.COMPLETED : WorkflowStatus.FAILED);
        }
        var unsafe = new StubWorkflowActions(null, Map.of("s1", StubWorkflowActions.Fault.TIMEOUT_ONCE), 20, false);
        var run = new Run(unsafe, "打开灯"); run.approve("APPROVED");
        assertThat(Collections.frequency(unsafe.calls(), "control")).isEqualTo(1);
        assertThat(run.state().workflow().failureCode()).isEqualTo("DEVICE_RESULT_UNKNOWN");
    }

    @Test void missingEvidenceFailsInsteadOfSilentlySkipping() throws Exception {
        var condition = new ExecutionPlan.Condition("EQ", new ExecutionPlan.Operand(new ExecutionPlan.Reference("s1", "power"), null),
                new ExecutionPlan.Operand(null, true), List.of());
        var control = new ExecutionPlan.Step("s2", PlanStepType.DEVICE_CONTROL, "control", "lamp", Map.of("deviceRef", 1L), List.of("s1"),
                Map.of(), condition, null, null);
        var actions = new StubWorkflowActions(new PlanProposal("PLAN", List.of(StubWorkflowActions.step("s1", PlanStepType.DEVICE_QUERY), control), null), Map.of(), 20, true);
        var run = new Run(actions, "fixture"); run.approve("APPROVED");
        assertThat(run.state().workflow().status()).isEqualTo(WorkflowStatus.FAILED);
        assertThat(actions.calls()).doesNotContain("control");
    }

    @Test void clarificationResumesPlannerAndOutOfScopeCompletesNormally() throws Exception {
        var run = new Run(new StubWorkflowActions(), "帮帮我");
        assertThat(run.state().workflow().status()).isEqualTo(WorkflowStatus.WAITING_INPUT);
        assertThat(run.state().workflow().inputRequestId()).isNotBlank();
        var p = run.state().plan();
        var updated = run.graph.updateState(run.config, Map.of(AssistantState.PLAN, new AssistantState.PlanContext(
                p.executionPlan(), p.currentStep(), Map.of("clarification", "什么是色温"), p.results(), p.candidateOutcome(), p.clarifyQuestion())));
        consume(run.graph.stream(GraphInput.resume(), updated));
        assertThat(run.state().workflow().status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(new Run(new StubWorkflowActions(), "天气如何").state().workflow().status()).isEqualTo(WorkflowStatus.COMPLETED);
    }

    @Test void expiredApprovalMustBeRenewedBeforeAnyQuery() throws Exception {
        var actions = new StubWorkflowActions(); var run = new Run(actions, "查询状态");
        var c = run.state().control(); var a = c.approvalRef();
        var updated = run.graph.updateState(run.config, Map.of(AssistantState.CONTROL,
                new AssistantState.ControlContext(c.command(), c.commandExecutionId(), c.permission(), c.risk(),
                        new AssistantState.Approval(a.approvalId(), a.stepId(), a.userId(), a.scopeHash(), "APPROVED", java.time.Instant.EPOCH),
                        c.idempotencyKey(), c.executionResult())));
        consume(run.graph.stream(GraphInput.resume(), updated));
        assertThat(actions.calls()).doesNotContain("query");
        assertThat(run.state().workflow().status()).isEqualTo(WorkflowStatus.WAITING_APPROVAL);
        assertThat(run.state().control().approvalRef().approvalId()).isNotEqualTo(a.approvalId());
    }

    @Test void resolvingAnUnspecifiedTargetDoesNotReplanOrApproveThePublishedStep() throws Exception {
        var actions = org.mockito.Mockito.spy(new StubWorkflowActions());
        org.mockito.Mockito.doAnswer(invocation -> {
            AssistantState state = invocation.getArgument(0);
            return state.plan().runtimeInputs().containsKey("clarification") ? Map.of("deviceRef", 1) : Map.of();
        }).when(actions).resolveTarget(org.mockito.ArgumentMatchers.any());
        var run = new Run(actions, "查询状态");
        assertThat(run.state().workflow().status()).isEqualTo(WorkflowStatus.WAITING_INPUT);
        var p = run.state().plan(); String hash = p.executionPlan().hash();
        var updated = run.graph.updateState(run.config, Map.of(AssistantState.PLAN, new AssistantState.PlanContext(
                p.executionPlan(), p.currentStep(), Map.of("clarification", "客厅灯"), p.results(), p.candidateOutcome(), p.clarifyQuestion())));
        consume(run.graph.stream(GraphInput.resume(), updated));
        assertThat(run.state().plan().executionPlan().hash()).isEqualTo(hash);
        assertThat(run.state().workflow().status()).isEqualTo(WorkflowStatus.WAITING_APPROVAL);
        assertThat(actions.calls()).containsExactly("plan");
    }

    static class Run {
        final CompiledGraph<AssistantState> graph;
        final RunnableConfig config = RunnableConfig.builder().threadId(UUID.randomUUID().toString()).build();
        Run(StubWorkflowActions actions, String message) throws Exception {
            graph = new MainGraphFactory(PROPERTIES, actions, Clock.systemUTC()).compile(new MemorySaver());
            consume(graph.stream(GraphInput.args(AssistantState.initial(new AssistantState.RequestContext(
                    config.threadId().orElseThrow(), 1, 1, message))), config));
        }
        AssistantState state() throws Exception { return graph.getState(config).state(); }
        void approve(String decision) throws Exception {
            var c = state().control(); var a = c.approvalRef();
            var updated = graph.updateState(config, Map.of(AssistantState.CONTROL, new AssistantState.ControlContext(c.command(),
                    c.commandExecutionId(), c.permission(), c.risk(), new AssistantState.Approval(a.approvalId(), a.stepId(), a.userId(),
                    a.scopeHash(), decision, a.expiresAt()), c.idempotencyKey(), c.executionResult())));
            consume(graph.stream(GraphInput.resume(), updated));
        }
    }
    @Test void queryAndControlRequireSeparateApprovalsAndExecuteInOrder() throws Exception {
        var actions = new StubWorkflowActions(new PlanProposal("PLAN", List.of(
                StubWorkflowActions.step("s1", PlanStepType.DEVICE_QUERY), StubWorkflowActions.step("s2", PlanStepType.DEVICE_CONTROL)), null), Map.of(), 20, true);
        var properties = new GraphProperties("stub", 8, 30, null, 2, 0, 300, 30, 300, 256);
        var factory = new MainGraphFactory(properties, actions, Clock.systemUTC());
        var graph = factory.compile(new MemorySaver());
        var config = RunnableConfig.builder().threadId("request-composite").build();
        var input = AssistantState.initial(new AssistantState.RequestContext("request-composite", 1, 1,
                "查询亮度，低于30就调到80"));
        consume(graph.stream(GraphInput.args(input), config));
        assertThat(actions.calls()).doesNotContain("query", "control");
        var state = graph.getState(config).state();
        var approval = state.control().approvalRef();
        var approved = new AssistantState.Approval(approval.approvalId(), approval.stepId(), 1,
                approval.scopeHash(), "APPROVED", approval.expiresAt());
        var control = state.control();
        var updated = graph.updateState(config, Map.of(AssistantState.CONTROL,
                new AssistantState.ControlContext(control.command(), control.commandExecutionId(),
                        control.permission(), control.risk(), approved, control.idempotencyKey(), Map.of())));
        consume(graph.stream(GraphInput.resume(), updated));
        assertThat(actions.calls()).contains("query").doesNotContain("control");
        var next = graph.getState(config).state().control().approvalRef();
        assertThat(next.stepId()).isNotEqualTo(approval.stepId());
        assertThat(next.status()).isEqualTo("PENDING");
    }

    static void consume(Iterable<? extends NodeOutput<?>> output) {
        for (var ignored : output) { /* Consume the real graph stream, not callback output. */ }
    }
}
