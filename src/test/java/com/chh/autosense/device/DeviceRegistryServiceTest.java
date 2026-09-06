package com.chh.autosense.device;

import com.chh.autosense.exception.ApiException;
import com.chh.autosense.exception.ErrorCode;
import com.chh.autosense.core.device.DeviceRegistryService;
import com.chh.autosense.core.device.client.DeviceServiceClient;
import com.chh.autosense.core.device.client.DeviceServiceClient.DeviceLookupResult;
import com.chh.autosense.core.device.client.DeviceServiceClient.DeviceServiceUnavailableException;
import com.chh.autosense.domain.entity.Device;
import com.chh.autosense.mapper.DeviceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeviceRegistryServiceTest {

    private DeviceServiceClient client;
    private DeviceMapper mapper;
    private DeviceRegistryService service;

    @BeforeEach
    void setUp() {
        client = mock(DeviceServiceClient.class);
        mapper = mock(DeviceMapper.class);
        service = new DeviceRegistryService(client, mapper);
    }

    @Test
    void 成功绑定只保存稳定字段且保留用户名称() {
        when(mapper.selectOneBySn("LITE123456789")).thenReturn(null);
        when(client.findDeviceBySn("LITE123456789")).thenReturn(found(
                "LITE123456789", "sim-name", "LITE", "LA001"));
        when(mapper.insert(any(Device.class))).thenAnswer(inv -> {
            inv.<Device>getArgument(0).setId(55L);
            return 1;
        });

        Device result = service.register(7L, "LITE123456789", "客厅灯");

        assertThat(result.getId()).isEqualTo(55L);
        assertThat(result.getUserId()).isEqualTo(7L);
        assertThat(result.getName()).isEqualTo("客厅灯");
        assertThat(result.getSimulatorName()).isEqualTo("sim-name");
        assertThat(result.getSn()).isEqualTo("LITE123456789");
        assertThat(result.getDeviceTypeCode()).isEqualTo("LITE");
        assertThat(result.getDeviceTypeId()).isEqualTo(1L);
        assertThat(result.getDeviceModelCode()).isEqualTo("LA001");
        assertThat(result.getDeviceModelId()).isEqualTo(2L);
    }

    @Test
    void 未支持类型仍允许绑定() {
        when(client.findDeviceBySn("CAMR123456789")).thenReturn(found(
                "CAMR123456789", "sim-camera", "CAMR", "CA001"));

        assertThat(service.register(1L, "CAMR123456789", "门口设备")
                .getDeviceTypeCode()).isEqualTo("CAMR");
        verify(mapper).insert(any(Device.class));
    }

    @Test
    void 非法SN和名称在访问数据库及上游前拒绝() {
        for (List<String> input : List.of(
                List.of("lite123456789", "灯"),
                List.of("LITE12345678", "灯"),
                List.of(" LITE123456789", "灯"),
                List.of("LITE123456789", "   "),
                List.of("LITE123456789", "x".repeat(65)))) {
            assertThatThrownBy(() -> service.register(1L, input.get(0), input.get(1)))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).errorCode())
                    .isEqualTo(ErrorCode.BAD_REQUEST);
        }
        verify(mapper, never()).selectOneBySn(any());
        verify(client, never()).findDeviceBySn(any());
    }

    @Test
    void 本地重复预检不访问模拟器() {
        when(mapper.selectOneBySn("LITE123456789")).thenReturn(new Device());

        assertCode(() -> service.register(1L, "LITE123456789", "灯"),
                ErrorCode.DEVICE_ALREADY_BOUND);
        verify(client, never()).findDeviceBySn(any());
    }

    @Test
    void 未发现及上游不可用均不写库() {
        when(client.findDeviceBySn("LITE123456781"))
                .thenReturn(new DeviceLookupResult(false, null, null, null,
                        null, null, null, null));
        assertCode(() -> service.register(1L, "LITE123456781", "灯"),
                ErrorCode.DEVICE_NOT_FOUND);

        when(client.findDeviceBySn("LITE123456782"))
                .thenThrow(new DeviceServiceUnavailableException("internal"));
        assertCode(() -> service.register(1L, "LITE123456782", "灯"),
                ErrorCode.DEVICE_SERVICE_UNAVAILABLE);
        verify(mapper, never()).insert(any(Device.class));
    }

    @Test
    void 唯一约束竞态统一映射重复绑定() {
        when(client.findDeviceBySn("LITE123456789")).thenReturn(found(
                "LITE123456789", "sim-name", "LITE", "LA001"));
        when(mapper.insert(any(Device.class)))
                .thenThrow(new DuplicateKeyException("uk_device_sn"));

        assertCode(() -> service.register(1L, "LITE123456789", "灯"),
                ErrorCode.DEVICE_ALREADY_BOUND);
    }

    @Test
    void 列表委托给按用户隔离查询() {
        when(mapper.selectMine(9L)).thenReturn(List.of(new Device()));
        assertThat(service.listMine(9L)).hasSize(1);
        verify(mapper).selectMine(9L);
    }

    private DeviceLookupResult found(String sn, String name, String type, String model) {
        return new DeviceLookupResult(true, 100L, sn, name,
                type, 1L, model, 2L);
    }

    private void assertCode(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(code);
    }
}
