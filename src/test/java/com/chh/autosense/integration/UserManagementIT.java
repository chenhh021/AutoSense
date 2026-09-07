package com.chh.autosense.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T045:US3 用户管理集成测试(quickstart 场景 7,Testcontainers MySQL/Redis)——
 * 注册→登录→me→logout→401 全链路;admin 列表/搜索/禁用/启用;禁用即令牌失效;
 * 越权访问他人资源(SC-008)，以及 SN 全局唯一不泄露原绑定用户。
 * data.sql 种子 admin/admin123 用于管理员流程。
 */
@org.junit.jupiter.api.Tag("device-simulator")
class UserManagementIT extends AbstractIntegrationIT {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper om;

    // ---------- 注册→登录→me→注销→401 全链路 ----------

    @Test
    void 注册登录注销全链路() throws Exception {
        String account = "it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        ResponseEntity<JsonNode> reg = post("/api/v1/users/register", Map.of(
                "userAccount", account, "userPassword", "pass1234",
                "confirmPassword", "pass1234"), null);
        assertThat(reg.getStatusCode().value()).as("注册: %s", reg.getBody()).isEqualTo(201);
        assertThat(reg.getBody().get("userName").asText()).isEqualTo(account);
        assertThat(reg.getBody().get("userRole").asText()).isEqualTo("user");
        assertThat(reg.getBody().has("userPassword")).isFalse();

        // 重复注册 → 409
        ResponseEntity<JsonNode> dup = post("/api/v1/users/register", Map.of(
                "userAccount", account, "userPassword", "pass1234",
                "confirmPassword", "pass1234"), null);
        assertThat(dup.getStatusCode().value()).isEqualTo(409);
        assertThat(dup.getBody().get("code").asText()).isEqualTo("ACCOUNT_EXISTS");

        // 弱密码 → 400
        ResponseEntity<JsonNode> weak = post("/api/v1/users/register", Map.of(
                "userAccount", account + "w", "userPassword", "12345678",
                "confirmPassword", "12345678"), null);
        assertThat(weak.getStatusCode().value()).isEqualTo(400);

        // 错误密码 → 401
        ResponseEntity<JsonNode> badLogin = post("/api/v1/users/login", Map.of(
                "userAccount", account, "userPassword", "wrong1234"), null);
        assertThat(badLogin.getStatusCode().value()).isEqualTo(401);

        // 正确登录 → token
        String token = login(account, "pass1234");

        // me
        ResponseEntity<JsonNode> me = get("/api/v1/users/me", token);
        assertThat(me.getStatusCode().value()).isEqualTo(200);
        assertThat(me.getBody().get("userAccount").asText()).isEqualTo(account);
        assertThat(me.getBody().has("userPassword")).isFalse();

        // logout → 同令牌立即失效
        ResponseEntity<String> logout = rest.exchange(url("/api/v1/users/logout"),
                HttpMethod.POST, new HttpEntity<>(auth(token)), String.class);
        assertThat(logout.getStatusCode().value()).isEqualTo(204);
        assertThat(get("/api/v1/users/me", token).getStatusCode().value()).isEqualTo(401);
    }

    // ---------- 管理员:列表/搜索/禁用/启用 ----------

    @Test
    void 管理员禁用启用与令牌失效() throws Exception {
        String account = "it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        post("/api/v1/users/register", Map.of(
                "userAccount", account, "userPassword", "pass1234",
                "confirmPassword", "pass1234"), null);
        String userToken = login(account, "pass1234");
        long userId = get("/api/v1/users/me", userToken).getBody().get("id").asLong();

        // 普通用户访问管理端点 → 403
        assertThat(get("/api/v1/admin/users", userToken).getStatusCode().value()).isEqualTo(403);

        // admin 登录(data.sql 种子)
        String adminToken = login("admin", "admin123");

        // 搜索找到目标用户
        ResponseEntity<JsonNode> list = get("/api/v1/admin/users?keyword=" + account, adminToken);
        assertThat(list.getStatusCode().value()).isEqualTo(200);
        assertThat(list.getBody().get("total").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(list.getBody().get("records").get(0).has("userPassword")).isFalse();

        // 禁用 → 200;原令牌立即失效;重新登录被拒
        ResponseEntity<JsonNode> disable = put("/api/v1/admin/users/" + userId + "/status",
                Map.of("disabled", true), adminToken);
        assertThat(disable.getStatusCode().value()).as("禁用: %s", disable.getBody()).isEqualTo(200);
        assertThat(get("/api/v1/users/me", userToken).getStatusCode().value()).isEqualTo(401);
        assertThat(post("/api/v1/users/login", Map.of(
                "userAccount", account, "userPassword", "pass1234"), null)
                .getStatusCode().value()).isEqualTo(401);

        // 启用 → 可重新登录
        ResponseEntity<JsonNode> enable = put("/api/v1/admin/users/" + userId + "/status",
                Map.of("disabled", false), adminToken);
        assertThat(enable.getStatusCode().value()).isEqualTo(200);
        assertThat(login(account, "pass1234")).isNotBlank();
    }

    @Test
    void 管理员不得禁用自身() throws Exception {
        String adminToken = login("admin", "admin123");
        long adminId = get("/api/v1/users/me", adminToken).getBody().get("id").asLong();

        ResponseEntity<JsonNode> resp = put("/api/v1/admin/users/" + adminId + "/status",
                Map.of("disabled", true), adminToken);
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
    }

    // ---------- 数据隔离(SC-008)----------

    @Test
    void 用户仅见本人设备与会话() throws Exception {
        String a = "it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String b = "it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        for (String account : new String[]{a, b}) {
            post("/api/v1/users/register", Map.of(
                    "userAccount", account, "userPassword", "pass1234",
                    "confirmPassword", "pass1234"), null);
        }
        String tokenA = login(a, "pass1234");
        String tokenB = login(b, "pass1234");

        JsonNode simulator = createSimulatorDevice("LA001", "sim-IT隔离灯");
        String sn = simulator.get("sn").asText();

        // A 按 SN 绑定设备，平台不得代建
        ResponseEntity<JsonNode> device = bindDevice(tokenA, sn, "IT隔离灯");
        assertThat(device.getStatusCode().value()).as("登记: %s", device.getBody()).isEqualTo(201);

        // B 的设备列表不含 A 的设备;B 的会话列表为空且不可见 A 的内容
        ResponseEntity<JsonNode> devicesB = get("/api/v1/devices", tokenB);
        assertThat(devicesB.getBody().get("devices").size()).isZero();
        ResponseEntity<JsonNode> sessionsB = get("/api/v1/sessions", tokenB);
        assertThat(sessionsB.getStatusCode().value()).isEqualTo(200);

        // 同用户和跨用户重复绑定返回完全相同的冲突语义，不披露归属。
        ResponseEntity<JsonNode> sameUserDuplicate = bindDevice(tokenA, sn, "重复灯");
        ResponseEntity<JsonNode> otherUserDuplicate = bindDevice(tokenB, sn, "抢占灯");
        for (ResponseEntity<JsonNode> duplicate
                : List.of(sameUserDuplicate, otherUserDuplicate)) {
            assertThat(duplicate.getStatusCode().value()).isEqualTo(409);
            assertThat(duplicate.getBody().path("code").asText())
                    .isEqualTo("DEVICE_ALREADY_BOUND");
            assertThat(duplicate.getBody().path("message").asText()).isEqualTo("该 SN 已绑定");
        }
    }

    // ---------- 辅助 ----------

    private String login(String account, String password) throws Exception {
        ResponseEntity<JsonNode> resp = post("/api/v1/users/login", Map.of(
                "userAccount", account, "userPassword", password), null);
        assertThat(resp.getStatusCode().value()).as("登录 %s: %s", account, resp.getBody())
                .isEqualTo(200);
        return resp.getBody().get("token").asText();
    }

    private HttpHeaders auth(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseEntity<JsonNode> get(String path, String token) {
        return rest.exchange(url(path), HttpMethod.GET,
                new HttpEntity<>(auth(token)), JsonNode.class);
    }

    private ResponseEntity<JsonNode> post(String path, Object body, String token) {
        HttpHeaders headers = token == null
                ? new HttpHeaders() {{ setContentType(MediaType.APPLICATION_JSON); }}
                : auth(token);
        return rest.exchange(url(path), HttpMethod.POST,
                new HttpEntity<>(body, headers), JsonNode.class);
    }

    private ResponseEntity<JsonNode> put(String path, Object body, String token) {
        return rest.exchange(url(path), HttpMethod.PUT,
                new HttpEntity<>(body, auth(token)), JsonNode.class);
    }
}
