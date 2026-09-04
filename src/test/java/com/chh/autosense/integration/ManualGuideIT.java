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
 * T030:US2 人工引导集成测试(quickstart 场景 2/3)——连真实 deviceSimulator。
 * MANUAL_ONLY 场景经模拟器预置 color_temperature=6500 命中"色温异常偏高"规则;
 * 独立网点查询路由(路由 4)不触碰任何设备。
 */
class ManualGuideIT extends AbstractIntegrationIT {

    private static final Pattern SESSION_ID = Pattern.compile("\"sessionId\":(\\d+)");

    @Autowired
    private TestRestTemplate rest;

    // ---------- 场景 2:规则命中 MANUAL_ONLY → 分步指引,设备不被改动 ----------

    @Test
    void 色温异常命中人工规则_给分步指引且设备未改动() throws Exception {
        String user = "user-201";
        JsonNode device = registerDevice(user, "IT色温灯", "LA001");
        long simulatorId = simulatorIdBySn(device.get("sn").asText());
        simulatorCommand(simulatorId, "set_color_temperature", Map.of("color_temperature", 6500));

        String body = ssePost(user, "/api/v1/sessions",
                Map.of("problem", "灯的颜色不太对,想调整一下"));
        assertThat(body).contains("GUIDED_MANUAL");
        assertThat(body).contains("event:conclusion").contains("UNFIXED_MANUAL_GUIDE");
        assertThat(body).contains("manualSteps");

        // 人工指引路径不得改动设备(FR-010):色温保持 6500
        assertThat(simulatorState(simulatorId).get("color_temperature").asInt()).isEqualTo(6500);
    }

    // ---------- 场景 3a:独立网点查询(带位置直达,不触碰设备) ----------

    @Test
    void 独立网点查询_位置在文本中直接给结果() {
        String body = ssePost("user-202", "/api/v1/sessions",
                Map.of("problem", "杭州市哪有售后网点"));
        assertThat(body).contains("AFTERSALES_LOOKUP");
        assertThat(body).contains("event:conclusion").contains("AFTERSALES_PROVIDED");
        // 杭州固定 mock 数据 2 家
        assertThat(body).contains("杭州西湖店").contains("杭州滨江店");
    }

    // ---------- 场景 3b:独立网点查询(先问位置) ----------

    @Test
    void 独立网点查询_缺位置先询问_补充后给结果() {
        String user = "user-203";
        String createBody = ssePost(user, "/api/v1/sessions",
                Map.of("problem", "附近有售后网点吗"));
        assertThat(createBody).contains("AWAITING_LOCATION").contains("event:awaiting");
        long sessionId = sessionIdOf(createBody);

        String body = ssePost(user, "/api/v1/sessions/" + sessionId + "/messages",
                Map.of("content", "上海"));
        assertThat(body).contains("event:conclusion").contains("AFTERSALES_PROVIDED");
        assertThat(body).contains("上海徐汇店");
    }

    // ---------- 场景 3c:未知位置回退官方客服(FR-011 兜底) ----------

    @Test
    void 未知位置回退官方客服() {
        String user = "user-204";
        String createBody = ssePost(user, "/api/v1/sessions",
                Map.of("problem", "附近有售后网点吗"));
        long sessionId = sessionIdOf(createBody);

        String body = ssePost(user, "/api/v1/sessions/" + sessionId + "/messages",
                Map.of("content", "火星"));
        assertThat(body).contains("event:conclusion").contains("AFTERSALES_PROVIDED");
        assertThat(body).contains("品牌官方客服");
    }

    // ---------- 辅助 ----------

    private HttpHeaders auth(String user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(user);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

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
        ResponseEntity<JsonNode> response =
                bindDevice(user, simulator.get("sn").asText(), name);
        assertThat(response.getStatusCode().value()).as("设备登记应 201: %s", response.getBody())
                .isEqualTo(201);
        return response.getBody();
    }

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
