package com.chh.autosense.config;

import com.chh.autosense.utils.LogSanitizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Read-only startup check: initializing tables does not migrate an existing schema. */
@Component
@org.springframework.context.annotation.Lazy(false)
@DependsOnDatabaseInitialization
@Slf4j
public class SessionSchemaValidator implements InitializingBean {
    private final JdbcTemplate jdbc;

    public SessionSchemaValidator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void afterPropertiesSet() {
        try {
            jdbc.queryForList("SELECT processing_message_id, processing_deadline_at FROM repair_session WHERE 1 = 0");
            jdbc.queryForList("SELECT active_workflow_request_id FROM repair_session WHERE 1 = 0");
            jdbc.queryForList("SELECT workflow_request_id, step_id, output_key FROM chat_message WHERE 1 = 0");
            jdbc.queryForList("SELECT request_id, step_id, command_execution_id, attempt_id, actor_user_id, "
                    + "event_type, operation_kind, event_sequence, event_key, result_code, chat_message_ref, schema_version "
                    + "FROM repair_action_log WHERE 1 = 0");
            jdbc.queryForList("SELECT request_id, user_id, session_id, report_id, origin_message_id, latest_input_message_id, "
                    + "graph_version, schema_version, status, suspended_status, current_step_index, plan_json, version, "
                    + "lease_owner, fence, lease_until, last_event_sequence, input_request_id, prompt, failure_code "
                    + "FROM workflow_execution WHERE 1 = 0");
            jdbc.queryForList("SELECT request_id, step_id, ordinal, type, status, input_json, input_hash, result_json, "
                    + "failure_code, certainty, retries_used, max_retries, active_attempt_id, command_execution_id "
                    + "FROM workflow_step WHERE 1 = 0");
            jdbc.queryForList("SELECT approval_id, request_id, step_id, user_id, scope_hash, operation_kind, device_refs, "
                    + "status, expires_at, decision_at, version FROM workflow_approval WHERE 1 = 0");
            jdbc.queryForList("SELECT thread_id, checkpoint_id, parent_checkpoint_id, node_id, next_node, state_payload, "
                    + "schema_version, graph_version, version, fence FROM workflow_checkpoint WHERE 1 = 0");
            jdbc.queryForList("SELECT command_id, request_id, step_id, operation_key, session_id, user_id, device_id, "
                    + "action_code, canonical_params, params_hash, approval_id, scope_hash, status, certainty, result_json, "
                    + "failure_code, active_attempt_id, attempt_count, retries_used, max_retries, version, fence, remote_operation_id "
                    + "FROM command_execution WHERE 1 = 0");
        } catch (BadSqlGrammarException e) {
            // Do not expose SQL, connection credentials or stored conversation content.
            throw new IllegalStateException("Session database schema is incompatible. "
                    + "For an existing database, apply scripts/migration/20260907-assistant-processing.sql "
                    + "to the configured datasource before starting AutoSense. "
                    + "Then apply scripts/migration/20260915-langgraph-workflow.sql. "
                    + "For a new database, initialize schema.sql explicitly. "
                    + "SQL_INIT_MODE=always does not upgrade existing tables.", LogSanitizer.diagnostic(e));
        }
        Long legacyActive = jdbc.queryForObject("SELECT COUNT(*) FROM repair_session "
                + "WHERE processing_message_id IS NOT NULL AND active_workflow_request_id IS NULL", Long.class);
        if (legacyActive != null && legacyActive > 0) {
            throw new IllegalStateException("Legacy requests have no workflow checkpoints. Drain or explicitly terminate "
                    + "them with the previous release before switching to graph execution. See "
                    + "specs/002-assistant-foundation/quickstart.md. No records were modified.");
        }
        log.info("Session database schema validated: result=OK");
    }
}
