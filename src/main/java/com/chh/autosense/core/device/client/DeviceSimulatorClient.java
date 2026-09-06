package com.chh.autosense.core.device.client;

import com.chh.autosense.config.DeviceServiceProperties;
import com.chh.autosense.core.device.spi.DeviceUnreachableException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * deviceSimulator HTTP 客户端(research R11,2026-08-27):唯一设备交互实现。
 * 诊断/命令契约见仓库根《后端接口说明.md》，按 SN 查询契约见
 * documents/新增接口说明-按SN查询设备.md。
 */
@Component
public class DeviceSimulatorClient implements DeviceServiceClient {

    private final RestClient client;

    @Autowired
    public DeviceSimulatorClient(DeviceServiceProperties props, RestClient.Builder builder) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int timeoutMs = (props.timeoutSeconds() == null ? 10 : props.timeoutSeconds()) * 1000;
        factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        this.client = builder
                .baseUrl(props.baseUrl())
                .requestFactory(factory)
                .build();
    }

    DeviceSimulatorClient(RestClient client) {
        this.client = client;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> getDeviceState(long simulatorDeviceId) {
        try {
            Map<String, Object> state = client.get()
                    .uri("/api/v1/devices/{id}/data", simulatorDeviceId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            return state == null ? Map.of() : new HashMap<>(state);
        } catch (HttpClientErrorException.NotFound e) {
            throw new DeviceUnreachableException(
                    "设备不存在或已停止运行(模拟器 id=%d)".formatted(simulatorDeviceId));
        } catch (HttpClientErrorException e) {
            throw new DeviceUnreachableException("设备状态读取失败: " + simulatorMessage(e));
        }
    }

    @Override
    public RepairResult executeCommand(long simulatorDeviceId, String command,
                                       Map<String, Object> parameters) {
        Map<String, Object> body = new HashMap<>();
        body.put("command", command);
        body.put("parameters", parameters == null ? Map.of() : parameters);
        try {
            client.post()
                    .uri("/api/v1/devices/{id}/commands", simulatorDeviceId)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            return new RepairResult(true, "command %s executed".formatted(command));
        } catch (HttpClientErrorException.NotFound e) {
            throw new DeviceUnreachableException(
                    "设备不存在或已停止运行(模拟器 id=%d)".formatted(simulatorDeviceId));
        } catch (HttpClientErrorException e) {
            // 失败即停(FR-017):如实透传模拟器错误,不重试
            return new RepairResult(false, simulatorMessage(e));
        }
    }

    @Override
    public RepairResult startDevice(long simulatorDeviceId) {
        try {
            client.post()
                    .uri("/api/v1/devices/{id}/start", simulatorDeviceId)
                    .retrieve()
                    .toBodilessEntity();
            return new RepairResult(true, "device started");
        } catch (HttpClientErrorException.NotFound e) {
            throw new DeviceUnreachableException(
                    "设备不存在(模拟器 id=%d)".formatted(simulatorDeviceId));
        } catch (HttpClientErrorException e) {
            return new RepairResult(false, simulatorMessage(e));
        }
    }

    @Override
    public DeviceLookupResult findDeviceBySn(String sn) {
        try {
            JsonNode response = client.get()
                    .uri("/api/v1/devices/by-sn/{sn}", sn)
                    .retrieve()
                    .body(JsonNode.class);
            return parseLookupResponse(sn, response);
        } catch (HttpClientErrorException.BadRequest e) {
            throw new DeviceLookupRequestException("SN 格式不合法");
        } catch (HttpServerErrorException | ResourceAccessException e) {
            throw unavailable(e);
        } catch (HttpClientErrorException e) {
            throw unavailable(e);
        } catch (DeviceServiceUnavailableException | DeviceLookupRequestException e) {
            throw e;
        } catch (RestClientException e) {
            throw unavailable(e);
        } catch (RuntimeException e) {
            throw unavailable(e);
        }
    }

    private DeviceLookupResult parseLookupResponse(String requestedSn, JsonNode response) {
        if (response == null || !response.has("exists") || !response.get("exists").isBoolean()) {
            throw new DeviceServiceUnavailableException("设备服务返回无效响应");
        }
        if (!response.get("exists").asBoolean()) {
            return new DeviceLookupResult(false, null, null, null,
                    null, null, null, null);
        }
        if (!validPositiveLong(response, "id")
                || !validPositiveLong(response, "device_type_id")
                || !validPositiveLong(response, "device_model_id")
                || !validText(response, "sn", 13)
                || !validText(response, "name", 128)
                || !validText(response, "device_type_code", 32)
                || !validText(response, "device_model_code", 32)
                || !requestedSn.equals(response.get("sn").asText())) {
            throw new DeviceServiceUnavailableException("设备服务返回无效响应");
        }
        return new DeviceLookupResult(
                true,
                response.get("id").asLong(),
                response.get("sn").asText(),
                response.get("name").asText(),
                response.get("device_type_code").asText(),
                response.get("device_type_id").asLong(),
                response.get("device_model_code").asText(),
                response.get("device_model_id").asLong());
    }

    private boolean validPositiveLong(JsonNode node, String field) {
        return node.hasNonNull(field) && node.get(field).isIntegralNumber()
                && node.get(field).canConvertToLong()
                && node.get(field).asLong() > 0;
    }

    private boolean validText(JsonNode node, String field, int maxLength) {
        return node.hasNonNull(field) && node.get(field).isTextual()
                && !node.get(field).asText().isBlank()
                && node.get(field).asText().length() <= maxLength;
    }

    private DeviceServiceUnavailableException unavailable(Exception cause) {
        return new DeviceServiceUnavailableException("设备服务暂不可用", cause);
    }

    /** 透传模拟器统一错误体的 code/message({code, message, details})。 */
    private String simulatorMessage(HttpClientErrorException e) {
        try {
            JsonNode error = e.getResponseBodyAs(JsonNode.class);
            if (error != null && error.hasNonNull("code")) {
                return "%s: %s".formatted(error.get("code").asText(),
                        error.path("message").asText(""));
            }
        } catch (Exception ignored) {
            // 响应体非 JSON 时退化到状态码
        }
        return "HTTP " + e.getStatusCode().value();
    }
}
