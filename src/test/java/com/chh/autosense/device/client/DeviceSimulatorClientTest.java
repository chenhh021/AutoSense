package com.chh.autosense.device.client;

import com.chh.autosense.config.DeviceServiceProperties;
import com.chh.autosense.device.client.DeviceServiceClient.DeviceLookupRequestException;
import com.chh.autosense.device.client.DeviceServiceClient.DeviceServiceUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DeviceSimulatorClientTest {

    private MockRestServiceServer server;
    private DeviceSimulatorClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://simulator.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new DeviceSimulatorClient(builder.build());
    }

    @Test
    void 完整存在响应只映射稳定字段() {
        server.expect(requestTo(
                        "http://simulator.test/api/v1/devices/by-sn/LITE123456789"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "exists":true,
                          "id":9,
                          "sn":"LITE123456789",
                          "name":"sim-la001",
                          "device_type_code":"LITE",
                          "device_type_id":1,
                          "device_model_code":"LA001",
                          "device_model_id":2,
                          "running_status":"running",
                          "state":{"brightness":50},
                          "created_at":"2026-09-03T10:00:00+08:00"
                        }
                        """, MediaType.APPLICATION_JSON));

        var result = client.findDeviceBySn("LITE123456789");

        assertThat(result.exists()).isTrue();
        assertThat(result.simulatorDeviceId()).isEqualTo(9L);
        assertThat(result.sn()).isEqualTo("LITE123456789");
        assertThat(result.name()).isEqualTo("sim-la001");
        assertThat(result.deviceTypeCode()).isEqualTo("LITE");
        assertThat(result.deviceModelCode()).isEqualTo("LA001");
        server.verify();
    }

    @Test
    void existsFalse是正常未发现() {
        server.expect(requestTo(
                        "http://simulator.test/api/v1/devices/by-sn/ZZZZ000000000"))
                .andRespond(withSuccess("{\"exists\":false}", MediaType.APPLICATION_JSON));

        assertThat(client.findDeviceBySn("ZZZZ000000000").exists()).isFalse();
    }

    @Test
    void 缺少exists或稳定字段或SN不一致均视为服务不可用() {
        assertMalformed("{}", "LITE123456781");
        assertMalformed("{\"exists\":true,\"id\":1}", "LITE123456782");
        assertMalformed("""
                {"exists":true,"id":1,"sn":"LITE000000000","name":"x",
                 "device_type_code":"LITE","device_type_id":1,
                 "device_model_code":"LA001","device_model_id":1}
                """, "LITE123456783");
    }

    @Test
    void 上游400映射请求错误() {
        server.expect(requestTo(
                        "http://simulator.test/api/v1/devices/by-sn/LITE123456789"))
                .andRespond(withBadRequest());

        assertThatThrownBy(() -> client.findDeviceBySn("LITE123456789"))
                .isInstanceOf(DeviceLookupRequestException.class);
    }

    @Test
    void 上游5xx和连接超时映射服务不可用() {
        server.expect(requestTo(
                        "http://simulator.test/api/v1/devices/by-sn/LITE123456781"))
                .andRespond(withServerError());
        assertThatThrownBy(() -> client.findDeviceBySn("LITE123456781"))
                .isInstanceOf(DeviceServiceUnavailableException.class);

        server.reset();
        server.expect(requestTo(
                        "http://simulator.test/api/v1/devices/by-sn/LITE123456782"))
                .andRespond(request -> {
                    throw new ResourceAccessException("read timed out");
                });
        assertThatThrownBy(() -> client.findDeviceBySn("LITE123456782"))
                .isInstanceOf(DeviceServiceUnavailableException.class);
    }

    private void assertMalformed(String body, String sn) {
        server.reset();
        server.expect(requestTo("http://simulator.test/api/v1/devices/by-sn/" + sn))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.findDeviceBySn(sn))
                .isInstanceOf(DeviceServiceUnavailableException.class);
    }
}
