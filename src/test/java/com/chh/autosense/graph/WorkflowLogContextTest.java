package com.chh.autosense.graph;

import com.chh.autosense.utils.LogContextUtils;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.util.SortedArrayStringMap;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class WorkflowLogContextTest {
    @Test void contextPatternOmitsAbsentFieldsAndStartupBlock() {
        var layout = PatternLayout.newBuilder().withPattern("%workflowContext%m").build();
        var fields = new SortedArrayStringMap(Map.of("userId", "1", "stepId", "s2"));
        assertThat(layout.toSerializable(Log4jLogEvent.newBuilder().setContextData(fields).build()))
                .isEqualTo("[userId=1, stepId=s2] ");
        assertThat(layout.toSerializable(Log4jLogEvent.newBuilder().setContextData(new SortedArrayStringMap()).build())).isEmpty();
    }
    @Test void scopesRejectControlCharactersAndRestorePreviousContext() {
        try (var first = LogContextUtils.install(Map.of("userId", "1"))) {
            try (var second = LogContextUtils.install(Map.of("stepId", "s2", "attemptId", "attempt:2", "workflowRequestId", "bad\nvalue", "secret", "hidden"))) {
                assertThat(LogContextUtils.snapshot()).containsExactlyInAnyOrderEntriesOf(Map.of("stepId", "s2", "attemptId", "attempt:2"));
            }
            assertThat(LogContextUtils.snapshot()).containsExactlyEntriesOf(Map.of("userId", "1"));
        }
    }
}
