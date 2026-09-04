package com.chh.autosense.unit;

import com.chh.autosense.device.spi.DeviceAdapter;
import com.chh.autosense.domain.model.Device;
import com.chh.autosense.repair.RepairExecutor;
import com.chh.autosense.repository.RepairActionLogMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RepairExecutorTest {

    private final RepairActionLogMapper logMapper = mock(RepairActionLogMapper.class);
    private final RepairExecutor executor = new RepairExecutor(logMapper, new ObjectMapper());

    @SuppressWarnings("unchecked")
    private DeviceAdapter adapter(List<String> whitelist,
                                  DeviceAdapter.RepairOutcome outcome) {
        DeviceAdapter adapter = mock(DeviceAdapter.class);
        when(adapter.supportedRepairActions()).thenReturn(whitelist);
        when(adapter.executeRepair(any(Device.class), any(String.class), any(Map.class)))
                .thenReturn(outcome);
        return adapter;
    }

    @Test
    void 白名单外动作直接拒绝且不触达设备() {
        DeviceAdapter adapter = adapter(List.of("bulb.powerCycle"), null);
        Device device = new Device();
        var outcome = executor.execute(1L, device, adapter, "bulb.selfDestruct", Map.of());

        assertThat(outcome.success()).isFalse();
        verify(adapter, times(0)).executeRepair(any(), any(), any());
        verify(logMapper).insert(any());
    }

    @Test
    void 失败即停_不自动重试_FR017() {
        DeviceAdapter adapter = adapter(List.of("bulb.powerCycle"),
                new DeviceAdapter.RepairOutcome(false, "device busy"));
        var outcome = executor.execute(1L, new Device(), adapter, "bulb.powerCycle", Map.of());

        assertThat(outcome.success()).isFalse();
        // 仅执行一次,无重试
        verify(adapter, times(1)).executeRepair(any(), any(), any());
    }

    @Test
    void 执行异常被捕获并记录不抛出_FR017() {
        DeviceAdapter adapter = adapter(List.of("bulb.powerCycle"), null);
        when(adapter.executeRepair(any(), any(), any()))
                .thenThrow(new RuntimeException("connection reset"));

        var outcome = executor.execute(1L, new Device(), adapter, "bulb.powerCycle", Map.of());

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("connection reset");
        verify(logMapper).insert(any());
    }
}
