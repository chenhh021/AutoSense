package com.chh.autosense.integration;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.MySQLContainer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import static org.assertj.core.api.Assertions.*;

/** Runs only against disposable containers; never touches a configured application datasource. */
@Tag("docker")
class WorkflowPersistenceMigrationIT {
    @Test void upgradesThreeExistingTablesWithoutInventingRecoverableHistory() throws Exception {
        try (var mysql = new MySQLContainer<>("mysql:8.0.18").withDatabaseName("workflow_migration")) {
            mysql.start();
            try (var connection = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())) {
                sql(connection, "CREATE TABLE repair_session (id BIGINT PRIMARY KEY, user_id BIGINT, status VARCHAR(32))");
                sql(connection, "CREATE TABLE chat_message (id BIGINT PRIMARY KEY, session_id BIGINT, role VARCHAR(16), content TEXT, created_at DATETIME)");
                sql(connection, "CREATE TABLE repair_action_log (id BIGINT PRIMARY KEY, session_id BIGINT, action_code VARCHAR(64), params JSON, result VARCHAR(16), message VARCHAR(1024), created_at DATETIME)");
                sql(connection, "INSERT INTO repair_session VALUES (42, 1, 'COMPLETED')");
                sql(connection, "INSERT INTO chat_message VALUES (71, 42, 'ASSISTANT', 'historical answer', NOW())");
                sql(connection, "INSERT INTO repair_action_log VALUES (93, 42, 'SET_BRIGHTNESS', NULL, 'SUCCESS', 'historical audit', NOW())");
                var validator = new com.chh.autosense.config.SessionSchemaValidator(new org.springframework.jdbc.core.JdbcTemplate(
                        new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true)));
                assertThatThrownBy(validator::afterPropertiesSet).hasMessageContaining("20260907-assistant-processing.sql");
                for (int attempt = 0; attempt < 2; attempt++) {
                    ScriptUtils.executeSqlScript(connection, new org.springframework.core.io.FileSystemResource(
                            "scripts/migration/20260907-assistant-processing.sql"));
                }
                var script = Files.readString(Path.of("scripts/migration/20260915-langgraph-workflow.sql"));
                ScriptUtils.executeSqlScript(connection, new ByteArrayResource(script.getBytes(StandardCharsets.UTF_8)));
                assertThatCode(validator::afterPropertiesSet).doesNotThrowAnyException();
                sql(connection, "UPDATE repair_session SET processing_message_id=71 WHERE id=42");
                assertThatThrownBy(validator::afterPropertiesSet).hasMessageContaining("Drain or explicitly terminate");
                assertThat(value(connection, "SELECT processing_message_id FROM repair_session WHERE id=42")).isEqualTo("71");
                sql(connection, "UPDATE repair_session SET processing_message_id=NULL WHERE id=42");
                assertThat(value(connection, "SELECT content FROM chat_message WHERE id=71")).isEqualTo("historical answer");
                assertThat(value(connection, "SELECT result FROM repair_action_log WHERE id=93")).isEqualTo("SUCCESS");
                assertThat(value(connection, "SELECT workflow_request_id FROM chat_message WHERE id=71")).isNull();
                assertThat(value(connection, "SELECT request_id FROM repair_action_log WHERE id=93")).isNull();
                assertThat(value(connection, "SELECT active_workflow_request_id FROM repair_session WHERE id=42")).isNull();
                assertThat(value(connection, "SELECT COUNT(*) FROM command_execution")).isEqualTo("0");
                assertThat(value(connection, "SELECT COUNT(*) FROM workflow_checkpoint")).isEqualTo("0");
                assertThat(value(connection, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()")).isEqualTo("8");
                assertThat(value(connection, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('workflow_event','audit_event','conversation')")).isEqualTo("0");
            }
        }
    }

    @Test void freshSchemaEnforcesMessageAndEventIdempotencyWithoutRestrictingLegacyNulls() throws Exception {
        try (var mysql = new MySQLContainer<>("mysql:8.0.18").withDatabaseName("workflow_fresh")) {
            mysql.start();
            try (var connection = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
                sql(connection, "INSERT INTO chat_message(session_id,role,content,created_at) VALUES (42,'USER','legacy one',NOW()),(42,'USER','legacy two',NOW())");
                sql(connection, "INSERT INTO chat_message(session_id,role,content,created_at,workflow_request_id,output_key) VALUES (42,'ASSISTANT','new',NOW(),'request','step:s1')");
                assertThatThrownBy(() -> sql(connection, "INSERT INTO chat_message(session_id,role,content,created_at,workflow_request_id,output_key) VALUES (42,'ASSISTANT','duplicate',NOW(),'request','step:s1')"))
                        .isInstanceOf(SQLException.class);
                sql(connection, "INSERT INTO repair_action_log(session_id,action_code,result,request_id,event_key,event_sequence) VALUES (42,'STEP_RESULT','SUCCESS','request','step:s1',1)");
                assertThatThrownBy(() -> sql(connection, "INSERT INTO repair_action_log(session_id,action_code,result,request_id,event_key,event_sequence) VALUES (42,'STEP_RESULT','SUCCESS','request','step:s1',2)"))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> sql(connection, "INSERT INTO repair_action_log(session_id,action_code,result,request_id,event_key,event_sequence) VALUES (42,'STEP_RESULT','SUCCESS','request','step:s2',1)"))
                        .isInstanceOf(SQLException.class);
            }
        }
    }

    private void sql(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) { statement.execute(sql); }
    }
    private String value(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue(); return result.getString(1);
        }
    }
}
