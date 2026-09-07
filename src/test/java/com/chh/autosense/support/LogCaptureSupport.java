package com.chh.autosense.support;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** A test-local appender; always restore the application's original logger configuration. */
public final class LogCaptureSupport implements AutoCloseable {
    private static final String NAME = "com.chh.autosense";
    private final LoggerContext context = (LoggerContext) LogManager.getContext(false);
    private final LoggerConfig previous = context.getConfiguration().getLoggers().get(NAME);
    private final List<LogEvent> events = new CopyOnWriteArrayList<>();
    private final PatternLayout layout = PatternLayout.newBuilder().withPattern("%m%n%ex").build();
    private final AbstractAppender appender = new AbstractAppender("testCapture", null, layout, true, Property.EMPTY_ARRAY) {
        @Override public void append(LogEvent event) { events.add(event.toImmutable()); }
    };

    public LogCaptureSupport() {
        appender.start();
        LoggerConfig capture = new LoggerConfig(NAME, Level.TRACE, false);
        capture.addAppender(appender, Level.TRACE, null);
        context.getConfiguration().removeLogger(NAME);
        context.getConfiguration().addLogger(NAME, capture);
        context.updateLoggers();
    }

    public List<LogEvent> events() { return List.copyOf(events); }
    public String rendered() { return events.stream().map(layout::toSerializable).reduce("", String::concat); }
    public void clear() { events.clear(); }

    @Override public void close() {
        context.getConfiguration().removeLogger(NAME);
        if (previous != null) context.getConfiguration().addLogger(NAME, previous);
        context.updateLoggers();
        appender.stop();
    }
}
