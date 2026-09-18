package com.chh.autosense.unit;

import com.chh.autosense.config.SessionSchemaValidator;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class WorkflowCutoverTest {
    @Test void legacyProcessingBlocksStartupWithoutChangingData() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(1L);
        assertThatThrownBy(() -> new SessionSchemaValidator(jdbc).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Drain or explicitly terminate", "No records were modified");
        verify(jdbc, never()).update(anyString());
    }
    @Test void drainedDatabaseStartsWithoutReadingLegacyRedis() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(0L);
        assertThatCode(() -> new SessionSchemaValidator(jdbc).afterPropertiesSet()).doesNotThrowAnyException();
        verify(jdbc, never()).update(anyString());
    }
}
