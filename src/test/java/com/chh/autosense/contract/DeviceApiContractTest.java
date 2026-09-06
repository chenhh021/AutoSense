package com.chh.autosense.contract;

import com.chh.autosense.exception.ApiException;
import com.chh.autosense.exception.ErrorCode;
import com.chh.autosense.controller.DeviceController;
import com.chh.autosense.core.device.DeviceAdapterRegistry;
import com.chh.autosense.core.device.DeviceRegistryService;
import com.chh.autosense.domain.entity.Device;
import com.chh.autosense.core.security.AuthUser;
import com.chh.autosense.core.security.BearerTokenAuthFilter;
import com.chh.autosense.core.security.SecurityConfig;
import com.chh.autosense.core.security.UserTokenResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DeviceController.class)
@Import({SecurityConfig.class, BearerTokenAuthFilter.class})
class DeviceApiContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DeviceRegistryService registryService;

    @MockitoBean
    private DeviceAdapterRegistry adapterRegistry;

    @MockitoBean
    private UserTokenResolver tokenResolver;

    @BeforeEach
    void setUp() {
        when(tokenResolver.resolve(anyString())).thenReturn(new AuthUser(1L));
    }

    @Test
    void 未认证返回401() throws Exception {
        mockMvc.perform(get("/api/v1/devices"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void 合法SN绑定返回稳定字段且在线状态为真() throws Exception {
        Device device = device("LITE123456789", "客厅灯", "sim-la001", "LITE", "LA001");
        when(registryService.register(1L, "LITE123456789", "客厅灯")).thenReturn(device);
        when(adapterRegistry.isSupported(device)).thenReturn(true);

        mockMvc.perform(post("/api/v1/devices")
                        .header("Authorization", "Bearer user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sn":"LITE123456789","name":"客厅灯"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(55))
                .andExpect(jsonPath("$.name").value("客厅灯"))
                .andExpect(jsonPath("$.simulatorName").value("sim-la001"))
                .andExpect(jsonPath("$.sn").value("LITE123456789"))
                .andExpect(jsonPath("$.deviceTypeCode").value("LITE"))
                .andExpect(jsonPath("$.deviceTypeId").value(1))
                .andExpect(jsonPath("$.deviceModelCode").value("LA001"))
                .andExpect(jsonPath("$.deviceModelId").value(1))
                .andExpect(jsonPath("$.supported").value(true))
                .andExpect(jsonPath("$.online").value(true))
                .andExpect(jsonPath("$.state").doesNotExist())
                .andExpect(jsonPath("$.runningStatus").doesNotExist())
                .andExpect(jsonPath("$.createdAt").doesNotExist());
    }

    @Test
    void 未支持类型仍绑定并返回supportedFalse() throws Exception {
        Device device = device("CAMR123456789", "门口设备", "sim-camera", "CAMR", "CA001");
        when(registryService.register(1L, "CAMR123456789", "门口设备")).thenReturn(device);
        when(adapterRegistry.isSupported(device)).thenReturn(false);

        mockMvc.perform(post("/api/v1/devices")
                        .header("Authorization", "Bearer user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sn\":\"CAMR123456789\",\"name\":\"门口设备\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.supported").value(false));
    }

    @Test
    void 列表投影稳定字段及动态支持性与在线状态() throws Exception {
        Device onlineDevice = device("LITE123456789", "客厅灯", "sim-la001", "LITE", "LA001");
        Device offlineDevice = device("LITE123456780", "卧室灯", "sim-la001-2", "LITE", "LA001");
        when(registryService.listMine(1L)).thenReturn(List.of(onlineDevice, offlineDevice));
        when(adapterRegistry.isSupported(any(Device.class))).thenReturn(true);
        when(registryService.isOnline(onlineDevice)).thenReturn(true);
        when(registryService.isOnline(offlineDevice)).thenReturn(false);

        mockMvc.perform(get("/api/v1/devices")
                        .header("Authorization", "Bearer user-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.devices[0].name").value("客厅灯"))
                .andExpect(jsonPath("$.devices[0].supported").value(true))
                .andExpect(jsonPath("$.devices[0].online").value(true))
                .andExpect(jsonPath("$.devices[0].status").doesNotExist())
                .andExpect(jsonPath("$.devices[1].name").value("卧室灯"))
                .andExpect(jsonPath("$.devices[1].online").value(false));
    }

    @Test
    void 非法请求返回400且不调用服务() throws Exception {
        for (String json : List.of(
                "{\"sn\":\"lite123456789\",\"name\":\"灯\"}",
                "{\"sn\":\"LITE12345678\",\"name\":\"灯\"}",
                "{\"sn\":\"LITE123456789\",\"name\":\"   \"}",
                "{\"name\":\"灯\"}")) {
            mockMvc.perform(post("/api/v1/devices")
                            .header("Authorization", "Bearer user-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        }
        verify(registryService, never()).register(anyLong(), anyString(), anyString());
    }

    @Test
    void 业务失败保持稳定错误语义() throws Exception {
        assertError("LITE123456781", ErrorCode.DEVICE_NOT_FOUND,
                "设备不存在或不在线", 404);
        assertError("LITE123456782", ErrorCode.DEVICE_ALREADY_BOUND,
                "该 SN 已绑定", 409);
        assertError("LITE123456783", ErrorCode.DEVICE_SERVICE_UNAVAILABLE,
                "设备服务暂不可用，请稍后重试", 503);
    }

    private void assertError(String sn, ErrorCode code, String message, int status) throws Exception {
        when(registryService.register(1L, sn, "灯"))
                .thenThrow(new ApiException(code, message));
        mockMvc.perform(post("/api/v1/devices")
                        .header("Authorization", "Bearer user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sn\":\"" + sn + "\",\"name\":\"灯\"}"))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(code.name()))
                .andExpect(jsonPath("$.message").value(message));
    }

    private Device device(String sn, String name, String simulatorName,
                          String typeCode, String modelCode) {
        Device device = new Device();
        device.setId(55L);
        device.setUserId(1L);
        device.setSn(sn);
        device.setName(name);
        device.setSimulatorName(simulatorName);
        device.setSimulatorDeviceId(100L);
        device.setDeviceTypeCode(typeCode);
        device.setDeviceTypeId(1L);
        device.setDeviceModelCode(modelCode);
        device.setDeviceModelId(1L);
        return device;
    }
}
