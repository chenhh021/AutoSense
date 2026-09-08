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
        } catch (BadSqlGrammarException e) {
            // Do not expose SQL, connection credentials or stored conversation content.
            throw new IllegalStateException("Session database schema is incompatible. "
                    + "For an existing database, apply scripts/migration/20260907-assistant-processing.sql "
                    + "to the configured datasource before starting AutoSense. "
                    + "For a new database, initialize schema.sql explicitly. "
                    + "SQL_INIT_MODE=always does not upgrade existing tables.", LogSanitizer.diagnostic(e));
        }
        log.info("Session database schema validated: result=OK");
    }
}
