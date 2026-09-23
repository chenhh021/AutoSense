package com.chh.autosense.unit;

import com.chh.autosense.core.device.client.DeviceServiceClient;
import com.chh.autosense.domain.entity.Device;
import com.chh.autosense.mapper.DeviceMapper;
import com.chh.autosense.service.device.DeviceRegistryService;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeviceRegistryServiceTest {
    @Test void bindsAnUnknownModelAndTreatsOnlineProbeAsDisplayOnly() {
        var client = mock(DeviceServiceClient.class); var mapper = mock(DeviceMapper.class);
        var service = new DeviceRegistryService(client, mapper);
        String sn = "LITE123456789";
        when(client.findDeviceBySn(sn)).thenReturn(new DeviceServiceClient.DeviceLookupResult(
                true, 91L, sn, "unknown model", "LITE", 1L, "NEW999", 999L));
        Device bound = service.register(42, sn, "lamp");
        assertThat(bound.getDeviceModelCode()).isEqualTo("NEW999");
        assertThat(bound.getUserId()).isEqualTo(42);
        verify(mapper).insert(bound);
        when(client.isDeviceOnline(sn)).thenThrow(new DeviceServiceClient.DeviceServiceUnavailableException("offline"));
        assertThat(service.isOnline(bound)).isFalse();
        assertThat(bound.getUserId()).isEqualTo(42);
        verify(client, never()).getDeviceState(anyLong());
        verify(client, never()).executeCommand(anyLong(), anyString(), anyMap());
    }
}
