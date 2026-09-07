package com.chh.autosense.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@org.junit.jupiter.api.Tag("device-simulator")
class DeviceBindingConcurrencyIT extends AbstractIntegrationIT {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 并发绑定同一SN只有一个成功且数据库仅一行() throws Exception {
        JsonNode simulator = createSimulatorDevice("LA001", "sim-concurrent");
        String sn = simulator.get("sn").asText();
        int workers = 8;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<Integer>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(workers)) {
            for (int i = 0; i < workers; i++) {
                int userId = 400 + i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    ResponseEntity<JsonNode> response =
                            bindDevice("user-" + userId, sn, "并发灯-" + userId);
                    return response.getStatusCode().value();
                }));
            }
            ready.await();
            start.countDown();

            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get());
            }
            assertThat(statuses).filteredOn(status -> status == 201).hasSize(1);
            assertThat(statuses).filteredOn(status -> status == 409).hasSize(workers - 1);
        }

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM device WHERE sn = ?", Integer.class, sn);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void 未知SN绑定失败零新增() throws Exception {
        // 合法格式但模拟器中不存在:查询返回不存在,绑定失败且不产生任何设备行
        String unknownSn = "ZZZZ999000001";
        ResponseEntity<JsonNode> resp = bindDevice("user-410", unknownSn, "未知设备");
        assertThat(resp.getStatusCode().value()).as("响应: %s", resp.getBody()).isEqualTo(404);
        assertThat(resp.getBody().path("code").asText()).isEqualTo("DEVICE_NOT_FOUND");

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM device WHERE sn = ?", Integer.class, unknownSn);
        assertThat(rows).isZero();
    }

    @Test
    void 他人重复绑定失败后原绑定保留不变() throws Exception {
        JsonNode simulator = createSimulatorDevice("LA001", "sim-retain");
        String sn = simulator.get("sn").asText();

        ResponseEntity<JsonNode> bound = bindDevice("user-420", sn, "原始名称");
        assertThat(bound.getStatusCode().value()).isEqualTo(201);
        long deviceId = bound.getBody().get("id").asLong();

        // 他人绑定同一 SN 失败
        ResponseEntity<JsonNode> conflict = bindDevice("user-421", sn, "抢占名称");
        assertThat(conflict.getStatusCode().value()).isEqualTo(409);

        // 原绑定行保持:归属/名称不变,仍只有一行
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT userId, name FROM device WHERE id = ?", deviceId);
        assertThat(((Number) row.get("userId")).longValue()).isEqualTo(420L);
        assertThat(row.get("name")).isEqualTo("原始名称");
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM device WHERE sn = ?", Integer.class, sn);
        assertThat(rows).isEqualTo(1);
    }
}
