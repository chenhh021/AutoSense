package com.chh.autosense.unit;

import com.chh.autosense.core.session.*;
import com.chh.autosense.core.device.rule.FaultRuleEngine;
import com.chh.autosense.core.device.spi.DeviceAdapter;
import com.chh.autosense.core.repair.RepairExecutor;
import com.chh.autosense.domain.dto.ConclusionDto;
import com.chh.autosense.domain.entity.*;
import com.chh.autosense.domain.enums.SessionStatus;
import com.chh.autosense.domain.message.SseEventStream;
import com.chh.autosense.mapper.*;
import com.chh.autosense.service.device.DeviceAdapterRegistryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Regression for the sole consumer of the removed SQL knowledge service. */
class RepairKnowledgeCompatibilityTest {
    @Test void failedRepairRetainsManualGuidanceOrAftersalesWithoutFurtherDeviceCommands() {
        for (boolean hasKnowledge : List.of(true, false)) {
            var sessions = mock(RepairSessionMapper.class);
            var devices = mock(DeviceMapper.class);
            var snapshots = mock(DiagnosticSnapshotMapper.class);
            var knowledge = mock(RepairKnowledgeMapper.class);
            var registry = mock(DeviceAdapterRegistryService.class);
            var executor = mock(RepairExecutor.class);
            var locks = mock(DeviceLockService.class);
            var contexts = mock(SessionContextStore.class);
            var transitions = mock(SessionTransitionLog.class);
            var runner = new RepairExecutionRunner(sessions, devices, snapshots, mock(ProblemReportMapper.class),
                    mock(ChatMessageMapper.class), registry, knowledge, executor, mock(FaultRuleEngine.class),
                    locks, contexts, transitions, new ObjectMapper());
            var session = new RepairSession();
            session.setId(1L);
            session.setDeviceId(2L);
            session.setStatus(SessionStatus.REPAIRING.name());
            var device = new Device();
            device.setId(2L);
            var adapter = mock(DeviceAdapter.class);
            when(sessions.selectOneById(1L)).thenReturn(session);
            when(devices.selectOneById(2L)).thenReturn(device);
            when(registry.diagnosticTypeOf(device)).thenReturn(Optional.of("light"));
            when(registry.adapterOf(device)).thenReturn(Optional.of(adapter));
            when(adapter.getDiagnostics(device)).thenReturn(Map.of("online", true));
            when(executor.execute(eq(1L), eq(device), eq(adapter), eq("repair"), anyMap()))
                    .thenReturn(new DeviceAdapter.RepairOutcome(false, "电源异常"));
            var entry = new RepairKnowledge();
            entry.setProblemPattern("灯泡、电源");
            entry.setManualSteps("检查电源\n联系售后");
            entry.setAutoExecutable(false);
            when(knowledge.selectListByQuery(any())).thenReturn(hasKnowledge ? List.of(entry) : List.of());
            when(snapshots.selectListByQuery(any())).thenReturn(List.of());
            var stream = mock(SseEventStream.class);
            runner.run(1L, "repair", Map.of(), stream);
            if (hasKnowledge) {
                var conclusion = ArgumentCaptor.forClass(Object.class);
                verify(stream).conclude(conclusion.capture());
                assertThat(((ConclusionDto) conclusion.getValue()).manualSteps()).containsExactly("检查电源", "联系售后");
                verify(transitions).transit(session, SessionStatus.VERIFYING, SessionStatus.GUIDED_MANUAL);
                verify(stream, never()).awaitUser(anyLong(), anyString());
            } else {
                verify(stream).awaitUser(eq(1L), contains("售后"));
                verify(transitions).transit(session, SessionStatus.GUIDED_AFTERSALES, SessionStatus.AWAITING_LOCATION);
            }
            verify(executor, times(1)).execute(anyLong(), any(), any(), anyString(), anyMap());
            verify(adapter, never()).executeRepair(any(), anyString(), anyMap());
            verify(locks).release(2L, 1L);
            verify(contexts).evict(1L);
        }
    }
}
