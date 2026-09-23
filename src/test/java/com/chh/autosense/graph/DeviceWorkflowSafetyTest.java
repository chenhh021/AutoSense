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
    final com.chh.autosense.service.DeviceCapabilityService capabilities = mock(com.chh.autosense.service.DeviceCapabilityService.class);
    final com.chh.autosense.service.DeviceOnlineInfoService online = mock(com.chh.autosense.service.DeviceOnlineInfoService.class);
    final com.chh.autosense.service.DeviceInfoService info = mock(com.chh.autosense.service.DeviceInfoService.class);
    final com.chh.autosense.core.security.DeviceToolAuthorizationService authorization = mock(com.chh.autosense.core.security.DeviceToolAuthorizationService.class);
    int fixtureBrightness = 20;
    DeviceQueryService query;
    Device device;
    @BeforeEach void setup() throws Exception {
        when(online.runtimeIdentity()).thenReturn(new com.chh.autosense.service.DeviceOnlineInfoService.RuntimeIdentity("real", "REAL", "isolated-test-endpoint"));
        query = new DeviceQueryService(devices, users, capabilities, online, info, authorization);
        device = new Device(); device.setId(7L); device.setUserId(1L); device.setName("lamp"); device.setSimulatorDeviceId(90L);
        device.setSn("TEST000000007"); device.setDeviceTypeCode("SMART_BULB"); device.setDeviceModelCode("MJDPL01YL");
        when(devices.selectOneById(7L)).thenReturn(device); when(devices.selectMine(1L)).thenReturn(List.of(device));
        var user = new User(); user.setId(1L); user.setIsDelete(0); when(users.selectByIdIncludingDeleted(1L)).thenReturn(user);
        when(locator.findCandidates(any(), any(), any())).thenReturn(List.of(device));
        when(adapters.diagnosticTypeOf(device)).thenReturn(Optional.of("smart_bulb"));
        String raw;
        try (var input = getClass().getResourceAsStream("/device/smart_bulb/MJDPL01YL.json")) {
            raw = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        var definition = com.chh.autosense.service.impl.DeviceCapabilityServiceImpl.parse("smart_bulb", "MJDPL01YL", raw);
        when(capabilities.get(anyString(), anyString())).thenReturn(definition);
        when(info.execute(any(), any())).thenAnswer(invocation -> {
            var attributes = new com.chh.autosense.ai.model.DeviceAttributeModels.Mjdpl01yl(true, fixtureBrightness,
                    new com.chh.autosense.ai.model.DeviceAttributeModels.Rgb(1, 2, 3), true, -50, "isolated-test");
            var runtime = new com.chh.autosense.ai.model.DeviceOnlineInfo("REAL", java.time.Instant.now(), definition.contentHash(), Map.of("get_properties", attributes));
            return new com.chh.autosense.ai.model.DeviceInfoResult(List.of(new com.chh.autosense.ai.model.DeviceInfoItem(device.getSn(), null,
                    runtime, new com.chh.autosense.ai.model.DeviceCapabilityInfo(definition.deviceType(), definition.deviceModel(), definition.rawJson(), definition.contentHash()), "OK", Map.of())), "OK");
        });
    }
    AssistantState state() {
        var state = new AssistantState(AssistantState.initial(new AssistantState.RequestContext("request", 1, 1, "lamp state")));
        var step = new ExecutionPlan.Step("s1", PlanStepType.DEVICE_QUERY, "read state", "lamp", Map.of("deviceRef", 7, "action", "state"), List.of(), Map.of(), null, null, null);
        return GraphUpdates.apply(state, Map.of(AssistantState.PLAN, new AssistantState.PlanContext(new ExecutionPlan(List.of(step), "hash"), 0, step.parameters(), Map.of(), "PLAN", ""),
                AssistantState.DEVICE, snapshot().withStep(Map.of("deviceRef", 7L), Map.of())));
    }
    AssistantState.DeviceContext snapshot() {
        return new AssistantState.DeviceContext((ResolvedDevice) null, Map.of(), List.of(
                new com.chh.autosense.ai.model.DeviceBasicInfo(7L, "lamp", "TEST000000007", "SMART_BULB", "MJDPL01YL", true, null)),
                Map.of(), Map.of(), true, java.time.Instant.now().toString(), "test-snapshot");
    }
    @Test void localResolutionAndPermissionChecksDoNotReadDevice() {
        assertThat(query.resolve(state())).containsEntry("deviceRef", 7L);
        query.validate(state()); verifyNoInteractions(client);
    }
    @Test void queryResultContainsBoundTargetTimestampAndOnlyAllowedParameters() {
        when(client.getDeviceState(90)).thenReturn(Map.of("brightness", 20, "secret", "hidden"));
        var result = query.query(state());
        assertThat(result).containsEntry("deviceRef", 7L).containsEntry("brightness", 20).containsKeys("observedAt", "evidence", "displayValues");
        assertThat(result.toString()).doesNotContain("hidden", "secret");
        verify(info, times(1)).execute(any(), any()); verifyNoInteractions(client);
    }
    @Test void ownershipOrAccountRevocationStopsBeforeEveryRequest() {
        device.setUserId(2L);
        assertThatThrownBy(() -> query.query(state())).isInstanceOf(SecurityException.class); verifyNoInteractions(client);
        device.setUserId(1L); when(users.selectByIdIncludingDeleted(1L)).thenReturn(null);
        assertThatThrownBy(() -> query.query(state())).isInstanceOf(SecurityException.class); verifyNoInteractions(client);
    }

    @Test void executionUsesPlannedIdAndNeverFallsBackToNameOrSnMatching() {
        var original = state();
        var p = original.plan();
        var missing = GraphUpdates.apply(original, Map.of(AssistantState.PLAN, new AssistantState.PlanContext(
                p.executionPlan(), 0, Map.of("clarification", "TEST000000007"), Map.of())));
        assertThatThrownBy(() -> query.resolve(missing)).isInstanceOf(com.chh.autosense.graph.node.StepFailure.class)
                .satisfies(error -> assertThat(((com.chh.autosense.graph.node.StepFailure) error).code()).isEqualTo("DEVICE_TARGET_REQUIRED"));
        device.setName("renamed lamp");
        assertThat(query.resolve(original)).containsEntry("deviceRef", 7L);
        verify(devices, never()).selectMine(anyLong());
        verifyNoInteractions(info);
    }

    @Test void changedSnOrModelCannotSilentlyRetargetThePlan() {
        var original = state();
        device.setSn("REBOUND");
        assertThatThrownBy(() -> query.resolve(original)).isInstanceOf(com.chh.autosense.graph.node.StepFailure.class);
        device.setSn("TEST000000007"); device.setDeviceModelCode("MJDP09YL");
        assertThatThrownBy(() -> query.resolve(original)).isInstanceOf(com.chh.autosense.graph.node.StepFailure.class);
        verifyNoInteractions(info);
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
        verify(info, times(1)).execute(any(), any()); verify(adapter, never()).executeRepair(any(), anyString(), anyMap());
        run.approve("APPROVED");
        verify(adapter, times(1)).executeRepair(eq(device), eq("set_brightness"), eq(Map.of("brightness", 80)));
        verify(info, times(1)).execute(any(), any());
        run.approve("REJECTED");
        assertThat(run.state().plan().results().get("s2").certainty()).isEqualTo(ExecutionPlan.Certainty.SUCCEEDED);
        assertThat(run.state().plan().results().get("s2").data()).containsEntry("verification", "NOT_PERFORMED");
        assertThat(run.state().workflow().failureCode()).isEqualTo("APPROVAL_REJECTED");
        verify(info, times(1)).execute(any(), any());
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
        clearInvocations(adapter, client, info);
        fixtureBrightness = 80;
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
            var cache = mock(com.chh.autosense.service.knowledge.UserAiServiceCache.class);
            var direct = new com.chh.autosense.ai.factory.DirectAnswerServiceFactory(
                    new com.chh.autosense.ai.factory.MockKnowledgeAiServicesFactory().knowledgeMockStreamingChatModel()).directAnswerService();
            when(cache.getOrCreate(any())).thenReturn(new com.chh.autosense.service.knowledge.UserAiServiceCache.UserAiServices(
                    direct, mock(com.chh.autosense.ai.EnhancedAnswerService.class), mock(com.chh.autosense.ai.EnhancedAnswerService.class)));
            var actions = new com.chh.autosense.graph.node.RealWorkflowActions(null,
                    new com.chh.autosense.core.session.memory.GraphChatMemoryAdapter(), new com.chh.autosense.utils.PromptInputEncoder(),
                    null, query, null, repair, null, null, cache) {
                @Override public PlanProposal plan(AssistantState state, java.time.Duration remaining) { return new PlanProposal("PLAN", condition ? List.of(read, write) : List.of(read, write, verify), null, snapshot()); }
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
