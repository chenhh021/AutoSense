package com.chh.autosense.graph;

import com.chh.autosense.core.device.DeviceQueryService;
import com.chh.autosense.core.device.client.DeviceServiceClient;
import com.chh.autosense.core.session.DeviceLocator;
import com.chh.autosense.domain.entity.*;
import com.chh.autosense.domain.enums.PlanStepType;
import com.chh.autosense.graph.node.GraphUpdates;
import com.chh.autosense.graph.state.*;
import com.chh.autosense.mapper.*;
import com.chh.autosense.service.device.DeviceAdapterRegistryService;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DeviceWorkflowSafetyTest {
    final DeviceMapper devices = mock(DeviceMapper.class);
    final UserMapper users = mock(UserMapper.class);
    final DeviceLocator locator = mock(DeviceLocator.class);
    final DeviceAdapterRegistryService adapters = mock(DeviceAdapterRegistryService.class);
    final DeviceServiceClient client = mock(DeviceServiceClient.class);
    DeviceQueryService query;
    Device device;
    @BeforeEach void setup() {
        query = new DeviceQueryService(devices, users, locator, adapters, client);
        device = new Device(); device.setId(7L); device.setUserId(1L); device.setName("lamp"); device.setSimulatorDeviceId(90L);
        device.setDeviceTypeCode("light"); device.setDeviceModelCode("MJDPL01YL");
        when(devices.selectOneById(7L)).thenReturn(device); when(devices.selectMine(1L)).thenReturn(List.of(device));
        var user = new User(); user.setId(1L); user.setIsDelete(0); when(users.selectByIdIncludingDeleted(1L)).thenReturn(user);
        when(locator.findCandidates(any(), any(), any())).thenReturn(List.of(device));
        when(adapters.diagnosticTypeOf(device)).thenReturn(Optional.of("smart_bulb"));
    }
    AssistantState state() {
        var state = new AssistantState(AssistantState.initial(new AssistantState.RequestContext("request", 1, 1, "lamp state")));
        var step = new ExecutionPlan.Step("s1", PlanStepType.DEVICE_QUERY, "read state", "lamp", Map.of("deviceRef", 7, "action", "state"), List.of(), Map.of(), null, null, null);
        return GraphUpdates.apply(state, Map.of(AssistantState.PLAN, new AssistantState.PlanContext(new ExecutionPlan(List.of(step), "hash"), 0, step.parameters(), Map.of(), "PLAN", ""),
                AssistantState.DEVICE, new AssistantState.DeviceContext(Map.of("deviceRef", 7), Map.of())));
    }
    @Test void localResolutionAndPermissionChecksDoNotReadDevice() {
        assertThat(query.resolve(state())).containsEntry("deviceRef", 7L);
        query.validate(state()); verifyNoInteractions(client);
    }
    @Test void queryResultContainsBoundTargetTimestampAndOnlyAllowedParameters() {
        when(client.getDeviceState(90)).thenReturn(Map.of("brightness", 20, "secret", "hidden"));
        var result = query.query(state());
        assertThat(result).containsEntry("deviceRef", 7L).containsEntry("brightness", 20).containsKeys("observedAt", "evidence", "answer");
        assertThat(result.toString()).doesNotContain("hidden", "secret");
        verify(client, times(1)).getDeviceState(90); verifyNoMoreInteractions(client);
    }
    @Test void ownershipOrAccountRevocationStopsBeforeEveryRequest() {
        device.setUserId(2L);
        assertThatThrownBy(() -> query.query(state())).isInstanceOf(SecurityException.class); verifyNoInteractions(client);
        device.setUserId(1L); when(users.selectByIdIncludingDeleted(1L)).thenReturn(null);
        assertThatThrownBy(() -> query.query(state())).isInstanceOf(SecurityException.class); verifyNoInteractions(client);
    }

    @Test void realQueryAndCommandAreSeparatedByApprovalAndRejectedVerificationKeepsSuccess() throws Exception {
        var adapter = mock(com.chh.autosense.core.device.spi.DeviceAdapter.class);
        when(adapters.adapterOf(device)).thenReturn(Optional.of(adapter));
        when(adapter.supportedRepairActions()).thenReturn(List.of("set_brightness"));
        when(adapter.executeRepair(eq(device), eq("set_brightness"), anyMap())).thenReturn(new com.chh.autosense.core.device.spi.DeviceAdapter.RepairOutcome(true, "ok"));
        when(client.getDeviceState(90)).thenReturn(Map.of("brightness", 20));
        var locks = mock(com.chh.autosense.core.session.DeviceLockService.class);
        when(locks.tryLock(eq(7L), anyString())).thenReturn(true);
        var repair = new com.chh.autosense.core.repair.RepairExecutor(query, adapters, locks);
        var run = new DeviceRun(repair, false);
        verifyNoInteractions(client); verify(adapter, never()).executeRepair(any(), anyString(), anyMap());
        run.approve("APPROVED");
        verify(client, times(1)).getDeviceState(90); verify(adapter, never()).executeRepair(any(), anyString(), anyMap());
        run.approve("APPROVED");
        verify(adapter, times(1)).executeRepair(eq(device), eq("set_brightness"), eq(Map.of("brightness", 80)));
        verify(client, times(1)).getDeviceState(90);
        run.approve("REJECTED");
        assertThat(run.state().plan().results().get("s2").certainty()).isEqualTo(ExecutionPlan.Certainty.SUCCEEDED);
        assertThat(run.state().plan().results().get("s2").data()).containsEntry("verification", "NOT_PERFORMED");
        assertThat(run.state().workflow().failureCode()).isEqualTo("APPROVAL_REJECTED");
        verify(client, times(1)).getDeviceState(90);
    }

    @Test void unknownWriteIsNeverRetriedAndFalseConditionSendsNoCommand() throws Exception {
        var adapter = mock(com.chh.autosense.core.device.spi.DeviceAdapter.class);
        when(adapters.adapterOf(device)).thenReturn(Optional.of(adapter));
        when(adapter.supportedRepairActions()).thenReturn(List.of("set_brightness"));
        when(adapter.executeRepair(any(), anyString(), anyMap())).thenThrow(new org.springframework.web.client.ResourceAccessException("timeout", new java.net.SocketTimeoutException()));
        var locks = mock(com.chh.autosense.core.session.DeviceLockService.class); when(locks.tryLock(eq(7L), anyString())).thenReturn(true);
        var repair = new com.chh.autosense.core.repair.RepairExecutor(query, adapters, locks);
        when(client.getDeviceState(90)).thenReturn(Map.of("brightness", 20));
        var run = new DeviceRun(repair, true); run.approve("APPROVED"); run.approve("APPROVED");
        assertThat(run.state().workflow().failureCode()).isEqualTo("DEVICE_RESULT_UNKNOWN");
        verify(adapter, times(1)).executeRepair(any(), anyString(), anyMap());
        clearInvocations(adapter, client);
        when(client.getDeviceState(90)).thenReturn(Map.of("brightness", 80));
        var skipped = new DeviceRun(repair, true); skipped.approve("APPROVED");
        assertThat(skipped.state().plan().results().get("s2").status()).isEqualTo(ExecutionPlan.StepStatus.SKIPPED);
        verify(adapter, never()).executeRepair(any(), anyString(), anyMap());
    }

    private class DeviceRun {
        final org.bsc.langgraph4j.CompiledGraph<AssistantState> graph;
        final org.bsc.langgraph4j.RunnableConfig config = org.bsc.langgraph4j.RunnableConfig.builder().threadId(UUID.randomUUID().toString()).build();
        DeviceRun(com.chh.autosense.core.repair.RepairExecutor repair, boolean condition) throws Exception {
            var read = new ExecutionPlan.Step("s1", PlanStepType.DEVICE_QUERY, "read", "lamp", Map.of("deviceRef", 7, "action", "state"), List.of(), Map.of(), null, null, null);
            var write = new ExecutionPlan.Step("s2", PlanStepType.DEVICE_CONTROL, "write", "lamp", Map.of("deviceRef", 7, "action", "setBrightness", "brightness", 80), List.of("s1"), Map.of(), condition ? new ExecutionPlan.Condition("LT", new ExecutionPlan.Operand(new ExecutionPlan.Reference("s1", "brightness"), null), new ExecutionPlan.Operand(null, 30), List.of()) : null, null, null);
            var verify = new ExecutionPlan.Step("s3", PlanStepType.DEVICE_QUERY, "verify", "lamp", read.parameters(), List.of("s2"), Map.of(), null, null, null);
            var actions = new com.chh.autosense.graph.node.RealWorkflowActions(null, null, null, null, query, null, repair) {
                @Override public PlanProposal plan(AssistantState state, java.time.Duration remaining) { return new PlanProposal("PLAN", condition ? List.of(read, write) : List.of(read, write, verify), null); }
                @Override public void prepareCommand(AssistantState state) { }
                @Override public void commitResult(AssistantState state, ExecutionPlan.Result result) { }
            };
            graph = new MainGraphFactory(new com.chh.autosense.config.GraphProperties("real", 8, 30, null, 2, 0, 300, 30, 300, 256), actions, java.time.Clock.systemUTC()).compile(new org.bsc.langgraph4j.checkpoint.MemorySaver());
            for (var ignored : graph.stream(org.bsc.langgraph4j.GraphInput.args(AssistantState.initial(new AssistantState.RequestContext(config.threadId().orElseThrow(), 1, 1, "test"))), config)) { }
        }
        AssistantState state() throws Exception { return graph.getState(config).state(); }
        void approve(String status) throws Exception {
            var c = state().control(); var a = c.approvalRef();
            var changed = graph.updateState(config, Map.of(AssistantState.CONTROL, new AssistantState.ControlContext(c.command(), c.commandExecutionId(), c.permission(), c.risk(),
                    new AssistantState.Approval(a.approvalId(), a.stepId(), a.userId(), a.scopeHash(), status, a.expiresAt()), c.idempotencyKey(), c.executionResult())));
            for (var ignored : graph.stream(org.bsc.langgraph4j.GraphInput.resume(), changed)) { }
        }
    }
}
