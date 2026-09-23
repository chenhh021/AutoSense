package com.chh.autosense.core.device.client;

import com.chh.autosense.config.DeviceServiceProperties;
import com.chh.autosense.core.device.spi.DeviceUnreachableException;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.MediaType;
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
 * 外部调用记录英文 operation/result/elapsedMs,不打印原始 SN/名称/响应正文。
 */
@Component
@Slf4j
public class DeviceSimulatorClient implements DeviceServiceClient {

    private final RestClient client;

    @Autowired
    public DeviceSimulatorClient(DeviceServiceProperties props, RestClient.Builder builder) {
        int timeoutMs = (props.timeoutSeconds() == null ? 10 : props.timeoutSeconds()) * 1000;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override protected void prepareConnection(java.net.HttpURLConnection connection, String method) throws java.io.IOException {
                super.prepareConnection(connection, method);
                long remaining = com.chh.autosense.graph.node.AttemptCalls.limit(Duration.ofMillis(timeoutMs)).toMillis();
                int bounded = (int) Math.max(1, Math.min(timeoutMs, remaining));
                connection.setConnectTimeout(bounded); connection.setReadTimeout(bounded);
            }
        };
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
        long startedNanos = System.nanoTime();
        try {
            Map<String, Object> state = client.get()
                    .uri("/api/v1/devices/{id}/data", simulatorDeviceId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            logCall("getState", "OK", startedNanos);
            return state == null ? Map.of() : new HashMap<>(state);
        } catch (HttpClientErrorException.NotFound e) {
            logCall("getState", "NOT_FOUND", startedNanos);
            throw new DeviceUnreachableException(
                    "设备不存在或已停止运行(模拟器 id=%d)".formatted(simulatorDeviceId));
        } catch (HttpClientErrorException e) {
            logCall("getState", "REJECTED", startedNanos);
            throw new DeviceUnreachableException("设备状态读取失败: " + simulatorMessage(e));
        } catch (RestClientException e) {
            logCall("getState", "UNAVAILABLE", startedNanos);
            throw e;
        }
    }

    @Override
    public RepairResult executeCommand(long simulatorDeviceId, String command,
                                       Map<String, Object> parameters) {
        Map<String, Object> body = new HashMap<>();
        body.put("command", command);
        body.put("parameters", parameters == null ? Map.of() : parameters);
        long startedNanos = System.nanoTime();
        try {
            client.post()
                    .uri("/api/v1/devices/{id}/commands", simulatorDeviceId)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            logCall("executeCommand", "OK", startedNanos);
            return new RepairResult(true, "command %s executed".formatted(command));
        } catch (HttpClientErrorException.NotFound e) {
            logCall("executeCommand", "NOT_FOUND", startedNanos);
            throw new DeviceUnreachableException(
                    "设备不存在或已停止运行(模拟器 id=%d)".formatted(simulatorDeviceId));
        } catch (HttpClientErrorException e) {
            logCall("executeCommand", "REJECTED", startedNanos);
            // 失败即停(FR-017):如实透传模拟器错误,不重试
            return new RepairResult(false, simulatorMessage(e));
        } catch (RestClientException e) {
            logCall("executeCommand", "UNAVAILABLE", startedNanos);
            throw e;
        }
    }

    @Override
    public RepairResult startDevice(long simulatorDeviceId) {
        long startedNanos = System.nanoTime();
        try {
            client.post()
                    .uri("/api/v1/devices/{id}/start", simulatorDeviceId)
                    .retrieve()
                    .toBodilessEntity();
            logCall("startDevice", "OK", startedNanos);
            return new RepairResult(true, "device started");
        } catch (HttpClientErrorException.NotFound e) {
            logCall("startDevice", "NOT_FOUND", startedNanos);
            throw new DeviceUnreachableException(
                    "设备不存在(模拟器 id=%d)".formatted(simulatorDeviceId));
        } catch (HttpClientErrorException e) {
            logCall("startDevice", "REJECTED", startedNanos);
            return new RepairResult(false, simulatorMessage(e));
        }
    }

    @Override
    public DeviceLookupResult findDeviceBySn(String sn) {
        long startedNanos = System.nanoTime();
        try {
            JsonNode response = client.get()
                    .uri("/api/v1/devices/by-sn/{sn}", sn)
                    .retrieve()
                    .body(JsonNode.class);
            DeviceLookupResult result = parseLookupResponse(sn, response);
            logCall("findBySn", Boolean.TRUE.equals(result.exists()) ? "HIT" : "MISS",
                    startedNanos);
            return result;
        } catch (HttpClientErrorException.BadRequest e) {
            logCall("findBySn", "BAD_REQUEST", startedNanos);
            throw new DeviceLookupRequestException("SN 格式不合法");
        } catch (HttpServerErrorException | ResourceAccessException e) {
            logCall("findBySn", "UNAVAILABLE", startedNanos);
            throw unavailable(e);
        } catch (HttpClientErrorException e) {
            logCall("findBySn", "UNAVAILABLE", startedNanos);
            throw unavailable(e);
        } catch (DeviceServiceUnavailableException | DeviceLookupRequestException e) {
            logCall("findBySn", "INVALID_RESPONSE", startedNanos);
            throw e;
        } catch (RestClientException e) {
            logCall("findBySn", "UNAVAILABLE", startedNanos);
            throw unavailable(e);
        } catch (RuntimeException e) {
            logCall("findBySn", "UNAVAILABLE", startedNanos);
            throw unavailable(e);
        }
    }

    @Override
    public boolean isDeviceOnline(String sn) {
        if (sn == null || !sn.matches("^[A-Z0-9]{4}[0-9]{9}$"))
            throw new DeviceLookupRequestException("SN 格式不合法");
        long startedNanos = System.nanoTime();
        try {
            var response = client.post().uri("/device/{sn}/get", sn)
                    .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                    .body(new byte[0]).retrieve().body(JsonNode.class);
            if (response == null || !response.isObject() || !response.path("sn").isTextual()
                    || !sn.equals(response.path("sn").textValue())
                    || !response.path("success").isBoolean() || !response.path("success").booleanValue()
                    || !response.path("properties").isObject() || !response.path("properties").path("online").isBoolean())
                throw new DeviceServiceUnavailableException("设备服务返回无效在线状态");
            boolean online = response.path("properties").path("online").booleanValue();
            // This pre-approval probe must not publish or cache the other returned attributes.
            logCall("probeOnline", online ? "ONLINE" : "OFFLINE", startedNanos);
            return online;
        } catch (HttpClientErrorException.NotFound e) {
            logCall("probeOnline", "OFFLINE", startedNanos);
            return false;
        } catch (HttpClientErrorException.BadRequest e) {
            logCall("probeOnline", "BAD_REQUEST", startedNanos);
            throw new DeviceLookupRequestException("SN 格式不合法");
        } catch (DeviceServiceUnavailableException e) {
            logCall("probeOnline", "INVALID_RESPONSE", startedNanos);
            throw e;
        } catch (RuntimeException e) {
            logCall("probeOnline", "UNAVAILABLE", startedNanos);
            throw unavailable(e);
        }
    }

    /** 外部调用统一英文日志:仅 operation/result/elapsedMs,不含 SN/名称/正文。 */
    private void logCall(String operation, String result, long startedNanos) {
        long elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000;
        log.info("Device service call completed: operation={}, result={}, elapsedMs={}",
                operation, result, elapsedMs);
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
