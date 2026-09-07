package com.chh.autosense.integration;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.GenericContainer;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 集成测试基座(T017,2026-08-27 澄清):Testcontainers MySQL 8 + Redis;
 * 设备交互连**真实 deviceSimulator**(外部服务,docker-compose 固定地址,
 * 默认 http://localhost:8081,可用 DEVICE_SERVICE_BASE_URL 覆盖),
 * 启动前轮询 /healthz 就绪探测,未就绪则以明确报错失败。
 * LLM 走内置 mock 桩(autosense.llm.mode=mock),售后走固定 mock 数据。
 * 需要 Docker + `docker compose up device-simulator`,标记 @Tag("docker"),默认 surefire 排除。
 */
@Tag("docker")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationIT {

    protected static final String SIMULATOR_BASE_URL =
            System.getProperty("device.service.base-url",
                    System.getenv().getOrDefault(
                            "DEVICE_SERVICE_BASE_URL", "http://localhost:8081"));

    // 单例容器(非 @Testcontainers 生命周期):跨 IT 类共享,JVM 退出时统一清理,
    // 避免多子类场景下容器被前一类的 afterAll 停掉导致后一类连接拒绝
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.18")
            .withDatabaseName("autosense")
            .withUsername("autosense")
            .withPassword("autosense");

    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis/redis-stack-server:latest").withExposedPorts(6379);

    static {
        MYSQL.start();
        REDIS.start();
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected TestRestTemplate restTemplate;

    private final List<Long> simulatorDeviceIds = new ArrayList<>();

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai");
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("autosense.llm.mode", () -> "mock");
        registry.add("autosense.device-service.base-url", () -> SIMULATOR_BASE_URL);
        registry.add("autosense.aftersales.mock-enabled", () -> "true");
        registry.add("autosense.auth.dev-mode", () -> "true");
    }

    @BeforeAll
    static void waitForSimulator(TestInfo testInfo) {
        if (!testInfo.getTags().contains("device-simulator")) return;
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)).build();
        HttpRequest probe = HttpRequest.newBuilder()
                .uri(URI.create(SIMULATOR_BASE_URL + "/healthz"))
                .timeout(Duration.ofSeconds(2)).GET().build();
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (http.send(probe, HttpResponse.BodyHandlers.discarding()).statusCode() == 200) {
                    return;
                }
            } catch (Exception ignored) {
                // 未就绪,继续等待
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new IllegalStateException(
                "deviceSimulator 未就绪: " + SIMULATOR_BASE_URL
                        + "。请先 `docker compose up -d device-simulator` 再运行集成测试。");
    }

    protected String url(String path) {
        return "http://localhost:" + port + path;
    }

    /**
     * 集成测试夹具只在 deviceSimulator 侧创建设备。AutoSense 的设备接口随后仅按 SN
     * 发现并绑定，确保测试不会掩盖“平台不得代建设备”的回归。
     */
    protected JsonNode createSimulatorDevice(String modelCode, String simulatorName) {
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                SIMULATOR_BASE_URL + "/api/v1/devices", HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "device_type_code", "LITE",
                        "device_model_code", modelCode,
                        "quantity", 1,
                        "name", simulatorName), jsonHeaders()),
                JsonNode.class);
        assertThat(response.getStatusCode().value())
                .as("模拟器创建设备应返回 201: %s", response.getBody())
                .isEqualTo(201);
        JsonNode device = response.getBody();
        assertThat(device).isNotNull();
        assertThat(device.path("id").canConvertToLong()).isTrue();
        assertThat(device.path("sn").asText()).matches("^[A-Z0-9]{4}[0-9]{9}$");
        simulatorDeviceIds.add(device.get("id").asLong());
        return device;
    }

    protected JsonNode findSimulatorDeviceBySn(String sn) {
        ResponseEntity<JsonNode> response = restTemplate.getForEntity(
                SIMULATOR_BASE_URL + "/api/v1/devices/by-sn/{sn}", JsonNode.class, sn);
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("模拟器按 SN 查询应成功: %s", response.getBody()).isTrue();
        return response.getBody();
    }

    protected JsonNode simulatorState(long simulatorDeviceId) {
        ResponseEntity<JsonNode> response = restTemplate.getForEntity(
                SIMULATOR_BASE_URL + "/api/v1/devices/{id}/data",
                JsonNode.class, simulatorDeviceId);
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("模拟器状态读取应成功: %s", response.getBody()).isTrue();
        return response.getBody();
    }

    protected void simulatorCommand(long simulatorDeviceId, String command,
                                    Map<String, Object> parameters) {
        ResponseEntity<String> response = simulatorPost(
                "/api/v1/devices/" + simulatorDeviceId + "/commands",
                Map.of("command", command,
                        "parameters", parameters == null ? Map.of() : parameters));
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("模拟器命令应成功: %s", response.getBody()).isTrue();
    }

    protected void stopSimulatorDevice(long simulatorDeviceId) {
        ResponseEntity<String> response = simulatorPost(
                "/api/v1/devices/" + simulatorDeviceId + "/stop", Map.of());
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("模拟器停止设备应成功: %s", response.getBody()).isTrue();
    }

    protected void startSimulatorDevice(long simulatorDeviceId) {
        ResponseEntity<String> response = simulatorPost(
                "/api/v1/devices/" + simulatorDeviceId + "/start", Map.of());
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("模拟器启动设备应成功: %s", response.getBody()).isTrue();
    }

    protected ResponseEntity<JsonNode> bindDevice(String bearerToken, String sn,
                                                  String displayName) {
        return restTemplate.exchange(url("/api/v1/devices"), HttpMethod.POST,
                new HttpEntity<>(Map.of("sn", sn, "name", displayName), authHeaders(bearerToken)),
                JsonNode.class);
    }

    protected int simulatorRunningDeviceCount() {
        ResponseEntity<JsonNode> response = restTemplate.getForEntity(
                SIMULATOR_BASE_URL + "/api/v1/devices", JsonNode.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return response.getBody() == null ? 0 : response.getBody().path("devices").size();
    }

    protected HttpHeaders authHeaders(String bearerToken) {
        HttpHeaders headers = jsonHeaders();
        headers.setBearerAuth(bearerToken);
        return headers;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseEntity<String> simulatorPost(String path, Object body) {
        return restTemplate.exchange(SIMULATOR_BASE_URL + path, HttpMethod.POST,
                new HttpEntity<>(body, jsonHeaders()), String.class);
    }

    @AfterEach
    void removeCreatedSimulatorDevices() {
        for (Long id : simulatorDeviceIds) {
            try {
                restTemplate.exchange(SIMULATOR_BASE_URL + "/api/v1/devices/{id}",
                        HttpMethod.DELETE, HttpEntity.EMPTY, Void.class, id);
            } catch (Exception ignored) {
                // 后续测试不能因夹具清理失败而隐藏原始断言结果。
            }
        }
        simulatorDeviceIds.clear();
    }
}
