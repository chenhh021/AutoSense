package com.chh.autosense.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

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
}
