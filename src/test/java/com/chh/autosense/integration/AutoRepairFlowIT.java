package com.chh.autosense.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T019:US1 自动修复闭环集成测试(quickstart 场景 0/1/4/5)——连真实 deviceSimulator。
 * 测试先在模拟器侧建机，再通过 FR-020 按 SN 绑定；故障场景经模拟器命令预置
 * (如 brightness=3 命中 brightness&lt;5 规则)。SSE 流同步关闭,响应体即完整事件流。
 * 修复失败/无重试由 RepairExecutorTest 单元覆盖(模拟器 400 透传)。
 */
class AutoRepairFlowIT extends AbstractIntegrationIT {

    private static final Pattern SESSION_ID = Pattern.compile("\"sessionId\":(\\d+)");

    @Autowired
    private TestRestTemplate rest;

    // ---------- 场景 0+1:登记 → 预置故障 → 确认门 → 流内修复 → FIXED ----------

    @Test
    void 灯泡亮度异常_确认后自动修复闭环() throws Exception {
        String user = "user-101";
        JsonNode device = registerDevice(user, "IT客厅灯", "LA001");
        long simulatorId = simulatorIdBySn(device.get("sn").asText());
        simulatorCommand(simulatorId, "set_brightness", Map.of("brightness", 3));

        String createBody = ssePost(user, "/api/v1/sessions",
                Map.of("problem", "客厅的灯太暗了,几乎看不见"));
        assertThat(createBody).contains("event:status").contains("CONFIRMING_REPAIR");
        assertThat(createBody).contains("event:awaiting");
        long sessionId = sessionIdOf(createBody);

        String confirmBody = ssePost(user, "/api/v1/sessions/" + sessionId + "/messages",
                Map.of("content", "确认执行", "confirmRepair", true));
        assertThat(confirmBody).contains("REPAIRING");
        assertThat(confirmBody).contains("event:conclusion").contains("FIXED");

        // 模拟器侧亮度已被修复动作调整到 80
        assertThat(simulatorState(simulatorId).get("brightness").asInt()).isEqualTo(80);
    }

    // ---------- 场景:多候选设备需确认(US1 场景 3) ----------

    @Test
    void 多候选设备需确认后继续修复() throws Exception {
        String user = "user-102";
        JsonNode first = registerDevice(user, "IT书房灯", "LA001");
        registerDevice(user, "IT卧室灯", "LB001");
        long simulatorId = simulatorIdBySn(first.get("sn").asText());
        simulatorCommand(simulatorId, "set_brightness", Map.of("brightness", 2));

        String createBody = ssePost(user, "/api/v1/sessions",
                Map.of("problem", "灯太暗了,帮忙处理一下"));
        assertThat(createBody).contains("DEVICE_CONFIRMING").contains("event:awaiting");
        long sessionId = sessionIdOf(createBody);

        String pickBody = ssePost(user, "/api/v1/sessions/" + sessionId + "/messages",
                Map.of("content", "1"));
        assertThat(pickBody).contains("CONFIRMING_REPAIR").contains("event:awaiting");

        String confirmBody = ssePost(user, "/api/v1/sessions/" + sessionId + "/messages",
                Map.of("content", "确认", "confirmRepair", true));
        assertThat(confirmBody).contains("event:conclusion").contains("FIXED");
        assertThat(simulatorState(simulatorId).get("brightness").asInt()).isEqualTo(80);
    }

    // ---------- 场景 5:设备停止 → 探测不可达 ----------

    @Test
    void 设备停止后探测返回DEVICE_UNREACHABLE() throws Exception {
        String user = "user-103";
        JsonNode device = registerDevice(user, "IT离线灯", "LA001");
        long simulatorId = simulatorIdBySn(device.get("sn").asText());
        stopSimulatorDevice(simulatorId);

        String body = ssePost(user, "/api/v1/sessions",
                Map.of("problem", "客厅的灯不亮了,完全没反应"));
        assertThat(body).contains("event:error").contains("DEVICE_UNREACHABLE");
    }

    // ---------- 场景 4:不支持设备类型 → error 事件,不触碰设备 ----------

    @Test
    void 不支持设备类型经error事件拒识() throws Exception {
        String body = ssePost("user-104", "/api/v1/sessions",
                Map.of("problem", "我的路由器坏了,完全连不上网"));
        assertThat(body).contains("event:error").contains("UNSUPPORTED_DEVICE_TYPE");
    }

    // ---------- 用户拒绝确认 → 设备不做改动 ----------

    @Test
    void 用户拒绝确认_设备保持原状() throws Exception {
        String user = "user-105";
        JsonNode device = registerDevice(user, "IT拒改灯", "LA001");
        long simulatorId = simulatorIdBySn(device.get("sn").asText());
        simulatorCommand(simulatorId, "set_brightness", Map.of("brightness", 4));

        String createBody = ssePost(user, "/api/v1/sessions",
                Map.of("problem", "灯太暗了看不清"));
        long sessionId = sessionIdOf(createBody);

        String rejectBody = ssePost(user, "/api/v1/sessions/" + sessionId + "/messages",
                Map.of("content", "先不用了", "confirmRepair", false));
        assertThat(rejectBody).contains("event:conclusion");
        // 未执行修复:亮度保持 4
        assertThat(simulatorState(simulatorId).get("brightness").asInt()).isEqualTo(4);
    }

    // ---------- 场景 6:终态续聊(R17)+ 常识直答不触碰设备(路由 1) ----------

    @Test
    void 终态会话续聊_常识问题直接回答且不开新一轮设备流程() throws Exception {
        String user = "user-106";
        registerDevice(user, "IT续聊灯", "LA001");

        String first = ssePost(user, "/api/v1/sessions",
                Map.of("problem", "灯泡一般能用多久?怎么延长寿命?"));
        assertThat(first).contains("ANSWERING").contains("event:token");
        assertThat(first).contains("event:conclusion").contains("ANSWERED");
        assertThat(first).contains("15000");
        long sessionId = sessionIdOf(first);

        // R17:终态后继续提问,同一会话开启新一轮
        String second = ssePost(user, "/api/v1/sessions/" + sessionId + "/messages",
                Map.of("content", "LA001 支持彩色吗?"));
        assertThat(second).contains("event:conclusion").contains("ANSWERED");
        assertThat(second).contains("单色灯泡");
    }

    // ---------- 辅助 ----------

    private HttpHeaders auth(String user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(user);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /** POST SSE 端点并返回完整事件流文本(编排同步执行,流已关闭)。 */
    private String ssePost(String user, String path, Object body) {
        ResponseEntity<String> response = rest.exchange(url(path), HttpMethod.POST,
                new HttpEntity<>(body, auth(user)), String.class);
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("SSE 响应应 2xx,实际 %s: %s", response.getStatusCode(), response.getBody())
                .isTrue();
        return response.getBody() == null ? "" : response.getBody();
    }

    private JsonNode registerDevice(String user, String name, String model) {
        JsonNode simulator = createSimulatorDevice(model, "sim-" + name);
        int beforeBind = simulatorRunningDeviceCount();
        ResponseEntity<JsonNode> response = bindDevice(
                user, simulator.get("sn").asText(), name);
        assertThat(response.getStatusCode().value()).as("设备登记应 201: %s", response.getBody())
                .isEqualTo(201);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("name").asText()).isEqualTo(name);
        assertThat(response.getBody().path("simulatorName").asText())
                .isEqualTo("sim-" + name);
        assertThat(response.getBody().path("deviceTypeCode").asText()).isEqualTo("LITE");
        assertThat(response.getBody().path("deviceModelCode").asText()).isEqualTo(model);
        assertThat(response.getBody().path("supported").asBoolean()).isTrue();
        assertThat(simulatorRunningDeviceCount()).isEqualTo(beforeBind);
        return response.getBody();
    }

    /** 按 sn 在模拟器侧查运行中设备,取其模拟器 id。 */
    private long simulatorIdBySn(String sn) {
        JsonNode lookup = findSimulatorDeviceBySn(sn);
        assertThat(lookup.path("exists").asBoolean()).isTrue();
        return lookup.get("id").asLong();
    }

    private long sessionIdOf(String sseBody) {
        Matcher m = SESSION_ID.matcher(sseBody);
        assertThat(m.find()).as("SSE 事件应包含 sessionId: %s", sseBody).isTrue();
        return Long.parseLong(m.group(1));
    }
}
