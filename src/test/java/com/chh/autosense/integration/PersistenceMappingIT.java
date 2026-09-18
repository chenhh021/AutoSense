package com.chh.autosense.integration;

import com.chh.autosense.core.security.AuthUser;
import com.chh.autosense.config.SessionSchemaValidator;
import com.chh.autosense.core.session.SessionLeaseService;
import com.chh.autosense.core.session.WorkflowPersistenceService;
import com.chh.autosense.core.session.memory.ConversationHistoryService;
import com.chh.autosense.domain.dto.ConclusionDto;
import com.chh.autosense.domain.entity.*;
import com.chh.autosense.exception.ApiException;
import com.chh.autosense.exception.ErrorCode;
import com.chh.autosense.mapper.*;
import com.chh.autosense.support.LogCaptureSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.chh.autosense.domain.enums.SessionStatus.*;
import static org.assertj.core.api.Assertions.*;

class PersistenceMappingIT extends AbstractIntegrationIT {
    @Autowired WorkflowPersistenceService persistence;
    @Autowired SessionLeaseService leases;
    @Autowired RepairSessionMapper sessions;
    @Autowired ProblemReportMapper reports;
    @Autowired ChatMessageMapper messages;
    @Autowired UserMapper users;
    @Autowired DeviceMapper devices;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired com.chh.autosense.config.GraphProperties properties;
    @Test void leaseOperationsCompareOwnersAtomically() {
        long sessionId = persistence.admit(user(), null, "lease").sessionId();
        var lease = leases.acquire(sessionId, 1);
        assertThat(lease).isNotNull();
        assertThat(leases.acquire(sessionId, 2)).isNull();
        assertThat(leases.renew(lease)).isTrue();
        assertThat(redis.getExpire("autosense:lock:session:" + sessionId)).isBetween(1L, (long) Math.min(30, properties.executionSliceTimeoutSeconds()));
        redis.opsForValue().set("autosense:lock:session:" + sessionId, "new-owner", Duration.ofSeconds(30));
        assertThat(leases.renew(lease)).isFalse();
        assertThat(leases.release(lease)).isFalse();
        assertThat(redis.opsForValue().get("autosense:lock:session:" + sessionId)).isEqualTo("new-owner");
        redis.delete("autosense:lock:session:" + sessionId);
        var next = leases.acquire(sessionId, 3);
        assertThat(leases.release(next)).isTrue();
    }

    @Test void sixEntityMappingsGeneratedIdsUpdatesAndLogicalDeletionRemainCompatible() {
        AuthUser auth = user();
        User user = users.selectOneById(auth.userId());
        assertThat(user.getCreateTime()).isNotNull();
        user.setUserName("updated"); users.update(user);
        assertThat(users.selectOneById(auth.userId()).getUserName()).isEqualTo("updated");
        Device device = new Device();
        device.setUserId(auth.userId()); device.setSn("TEST" + String.format("%09d", auth.userId()));
        device.setName("display"); device.setSimulatorName("fixture"); device.setSimulatorDeviceId(1L);
        device.setDeviceTypeCode("LITE"); device.setDeviceTypeId(1L);
        device.setDeviceModelCode("LA001"); device.setDeviceModelId(1L);
        devices.insert(device);
        assertThat(device.getId()).isPositive();
        device.setName("renamed"); devices.update(device);
        assertThat(devices.selectOneById(device.getId()).getName()).isEqualTo("renamed");
        var accepted = persistence.admit(auth, null, "mapping");
        assertThat(accepted.sessionId()).isPositive(); assertThat(accepted.messageId()).isPositive();
        assertThat(accepted.reportId()).isPositive();
        assertThat(reports.selectOneById(accepted.reportId()).getRawText()).isEqualTo("mapping");
        assertThat(messages.selectOneById(accepted.messageId()).getCreatedAt()).isNotNull();
        assertThat(count("repair_action_log", accepted.sessionId())).isPositive();
        var session = sessions.selectOneById(accepted.sessionId());
        session.setConclusion("answer"); sessions.update(session);
        assertThat(sessions.selectOneById(accepted.sessionId()).getConclusion()).isEqualTo("answer");
        users.deleteById(auth.userId());
        assertThat(users.selectOneById(auth.userId())).isNull();
        assertThat(jdbc.queryForObject("SELECT isDelete FROM user WHERE id = ?", Integer.class, auth.userId())).isEqualTo(1);
    }

    private AuthUser user() {
        User user = new User();
        user.setUserAccount("it_" + UUID.randomUUID().toString().replace("-", ""));
        user.setUserPassword("isolated-test-hash"); user.setUserName("fixture");
        user.setUserRole("user"); user.setIsDelete(0); users.insert(user);
        return new AuthUser(user.getId());
    }

    private long count(String table, long sessionId) {
        if (!List.of("chat_message", "repair_action_log").contains(table)) throw new IllegalArgumentException();
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE session_id = ?", Long.class, sessionId);
    }
}
