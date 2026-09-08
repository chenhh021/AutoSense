package com.chh.autosense.integration;

import com.chh.autosense.core.security.AuthUser;
import com.chh.autosense.config.SessionSchemaValidator;
import com.chh.autosense.core.session.SessionLeaseService;
import com.chh.autosense.core.session.SessionProcessingService;
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

class SessionProcessingIT extends AbstractIntegrationIT {
    @Autowired SessionProcessingService processing;
    @Autowired ConversationHistoryService history;
    @Autowired SessionLeaseService leases;
    @Autowired RepairSessionMapper sessions;
    @Autowired ProblemReportMapper reports;
    @Autowired ChatMessageMapper messages;
    @Autowired RepairActionLogMapper audit;
    @Autowired UserMapper users;
    @Autowired DeviceMapper devices;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired PlatformTransactionManager transactionManager;

    @Test void concurrentAdmissionStoresExactlyOneInputAndKeepsTheWinningPointer() throws Exception {
        AuthUser user = user();
        var first = processing.create(user, "first");
        assertThat(processing.finish(first, CLARIFYING, "Which capability?", null, null)).isTrue();
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var calls = new ArrayList<java.util.concurrent.Future<Object>>();
            for (int i = 0; i < 2; i++) {
                int index = i;
                calls.add(workers.submit(() -> {
                    start.await();
                    try { return processing.accept(user, first.sessionId(), "race-" + index, null); }
                    catch (ApiException e) { return e.errorCode(); }
                }));
            }
            start.countDown();
            List<Object> results = List.of(calls.get(0).get(10, TimeUnit.SECONDS), calls.get(1).get(10, TimeUnit.SECONDS));
            assertThat(results).filteredOn(o -> o == ErrorCode.SESSION_BUSY).hasSize(1);
            var winner = (SessionProcessingService.Accepted) results.stream()
                    .filter(SessionProcessingService.Accepted.class::isInstance).findFirst().orElseThrow();
            assertThat(sessions.selectOneById(first.sessionId()).getProcessingMessageId()).isEqualTo(winner.messageId());
            assertThat(count("chat_message", first.sessionId())).isEqualTo(3);
            assertThat(winner.round()).isEqualTo(1);
            assertThat(processing.finish(first, FAILED_REQUEST, "late", null, ErrorCode.INTERNAL_ERROR)).isFalse();
            assertThat(processing.expire(first.sessionId(), first.messageId())).isFalse();
            assertThat(processing.isCurrent(winner)).isTrue();
        }
    }

    @Test void deadlineUsesSeparateExpiryConditionAndCannotClearANewerRound() {
        AuthUser user = user();
        var first = processing.create(user, "deadline");
        assertThat(processing.expire(first.sessionId(), first.messageId())).isFalse();
        jdbc.update("UPDATE repair_session SET processing_deadline_at = DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 1 SECOND) WHERE id = ?", first.sessionId());
        assertThat(processing.finish(first, FAILED_REQUEST, "late", null, ErrorCode.INTERNAL_ERROR)).isFalse();
        assertThat(processing.expire(first.sessionId(), first.messageId())).isTrue();
        var expired = sessions.selectOneById(first.sessionId());
        assertThat(expired.getStatus()).isEqualTo("FAILED_REQUEST");
        assertThat(expired.getConclusionExtra()).contains("REQUEST_TIMEOUT");
        assertThat(expired.getProcessingMessageId()).isNull();
        assertThat(expired.getProcessingDeadlineAt()).isNull();
        var next = processing.accept(user, first.sessionId(), null, true);
        assertThat(next.round()).isEqualTo(2);
        assertThat(messages.selectOneById(next.messageId()).getContent()).isEqualTo("确认继续设备操作");
        assertThat(processing.expire(first.sessionId(), first.messageId())).isFalse();
        assertThat(processing.isCurrent(next)).isTrue();
        assertThatThrownBy(() -> processing.accept(new AuthUser(user.userId() + 10000), first.sessionId(), "foreign", null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.DEVICE_FORBIDDEN));
    }

    @Test void finalizationRollbackLeavesMessagesStatePointerAndAuditTogether() {
        var accepted = processing.create(user(), "rollback");
        long messageCount = count("chat_message", accepted.sessionId());
        long auditCount = count("repair_action_log", accepted.sessionId());
        try (var logs = new LogCaptureSupport()) {
            assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                assertThat(processing.finish(accepted, FAILED_REQUEST, "failure", null, ErrorCode.INTERNAL_ERROR)).isTrue();
                throw new IllegalStateException("force rollback");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(logs.rendered()).doesNotContain("Session request completed", "Session state changed");
        }
        assertThat(count("chat_message", accepted.sessionId())).isEqualTo(messageCount);
        assertThat(count("repair_action_log", accepted.sessionId())).isEqualTo(auditCount);
        assertThat(sessions.selectOneById(accepted.sessionId()).getStatus()).isEqualTo("ROUTING");
        assertThat(processing.isCurrent(accepted)).isTrue();
    }

    @Test void databaseHistoryIsImmutableBoundedOrderedAndExcludesCurrentAndInternalMessages() {
        AuthUser user = user();
        var first = processing.create(user, "original");
        assertThat(processing.finish(first, CLARIFYING, "clarify", null, null)).isTrue();
        for (int i = 0; i < 30; i++) {
            ChatMessage message = new ChatMessage();
            message.setSessionId(first.sessionId());
            message.setRole(i % 2 == 0 ? "USER" : "ASSISTANT");
            message.setContent("history-" + i);
            messages.insert(message);
        }
        ChatMessage internal = new ChatMessage();
        internal.setSessionId(first.sessionId()); internal.setRole("SYSTEM"); internal.setContent("internal-secret");
        messages.insert(internal);
        var current = processing.accept(user, first.sessionId(), "current", null);
        redis.opsForValue().set("autosense:memory:" + first.sessionId(), "old-untrusted", Duration.ofMinutes(1));
        var snapshot = history.snapshot(current);
        assertThat(snapshot.messages()).hasSize(20);
        assertThat(snapshot.messages()).extracting(m -> m.content()).containsExactlyElementsOf(
                java.util.stream.IntStream.range(10, 30).mapToObj(i -> "history-" + i).toList());
        assertThat(snapshot.messages()).allMatch(m -> m.id() < current.messageId());
        assertThatThrownBy(() -> snapshot.messages().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void leaseOperationsCompareOwnersAtomically() {
        long sessionId = processing.create(user(), "lease").sessionId();
        var lease = leases.acquire(sessionId, 1);
        assertThat(lease).isNotNull();
        assertThat(leases.acquire(sessionId, 2)).isNull();
        assertThat(leases.renew(lease)).isTrue();
        assertThat(redis.getExpire("autosense:lock:session:" + sessionId)).isBetween(1L, 30L);
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
        var accepted = processing.create(auth, "mapping");
        assertThat(accepted.sessionId()).isPositive(); assertThat(accepted.messageId()).isPositive();
        assertThat(accepted.reportId()).isPositive();
        assertThat(reports.selectOneById(accepted.reportId()).getRawText()).isEqualTo("mapping");
        assertThat(messages.selectOneById(accepted.messageId()).getCreatedAt()).isNotNull();
        assertThat(count("repair_action_log", accepted.sessionId())).isPositive();
        assertThat(processing.advance(accepted, ANSWERING, "KNOWLEDGE")).isTrue();
        assertThat(processing.finish(accepted, COMPLETED_ANSWERED, "answer",
                new ConclusionDto("ANSWERED", "answer", null, null, null, null), null)).isTrue();
        assertThat(sessions.selectOneById(accepted.sessionId()).getConclusion()).isEqualTo("answer");
        users.deleteById(auth.userId());
        assertThat(users.selectOneById(auth.userId())).isNull();
        assertThat(jdbc.queryForObject("SELECT isDelete FROM user WHERE id = ?", Integer.class, auth.userId())).isEqualTo(1);
    }

    @Test void forwardMigrationIsRepeatableAndPreservesOldRecords() throws Exception {
        String database = "foundation_migration_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword())) {
            try (var create = connection.createStatement()) { create.execute("CREATE DATABASE " + database); }
            connection.setCatalog(database);
            try (var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE repair_session (id BIGINT PRIMARY KEY, status VARCHAR(32))");
            statement.execute("INSERT INTO repair_session VALUES (42, 'COMPLETED_FIXED')");
            var validator = new SessionSchemaValidator(new JdbcTemplate(new SingleConnectionDataSource(connection, true)));
            assertThatThrownBy(validator::afterPropertiesSet)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("scripts/migration/20260907-assistant-processing.sql");
            // Re-running the fresh-database initializer leaves an existing table unchanged.
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
            assertThatThrownBy(validator::afterPropertiesSet)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("SQL_INIT_MODE=always does not upgrade existing tables");
            // Also cover a partially applied migration (only one of the two columns exists).
            statement.execute("ALTER TABLE repair_session ADD COLUMN processing_message_id BIGINT NULL");
            assertThatThrownBy(validator::afterPropertiesSet).isInstanceOf(IllegalStateException.class);
            for (int i = 0; i < 2; i++) {
                ScriptUtils.executeSqlScript(connection, new FileSystemResource("scripts/migration/20260907-assistant-processing.sql"));
                assertThatCode(validator::afterPropertiesSet).doesNotThrowAnyException();
            }
            try (var rows = statement.executeQuery("SELECT * FROM repair_session WHERE id = 42")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getString("status")).isEqualTo("COMPLETED_FIXED");
                assertThat(rows.getObject("processing_message_id")).isNull();
                assertThat(rows.getObject("processing_deadline_at")).isNull();
            }
            }
        }
        // The whole database belongs to the temporary Testcontainer and is removed with it.
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
