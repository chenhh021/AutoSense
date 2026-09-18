package com.chh.autosense.utils;

import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.pattern.*;
import java.util.List;
import java.util.StringJoiner;

/** Prints only present context fields, including no bracket block for service startup logs. */
@Plugin(name = "WorkflowLogContext", category = PatternConverter.CATEGORY)
@ConverterKeys("workflowContext")
public final class WorkflowLogContextConverter extends LogEventPatternConverter {
    private static final List<String> KEYS = List.of("requestId", "userId", "sessionId", "messageId", "round", "deviceId", "workflowRequestId", "stepId", "attemptId");
    private WorkflowLogContextConverter() { super("workflowContext", "workflowContext"); }
    public static WorkflowLogContextConverter newInstance(String[] options) { return new WorkflowLogContextConverter(); }
    @Override public void format(LogEvent event, StringBuilder target) {
        var fields = new StringJoiner(", ", "[", "] ");
        int count = 0;
        for (String key : KEYS) {
            Object value = event.getContextData().getValue(key);
            if (value != null && !value.toString().isBlank()) { fields.add(key + "=" + value); count++; }
        }
        if (count > 0) target.append(fields);
    }
}
