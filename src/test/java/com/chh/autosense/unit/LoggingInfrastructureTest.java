package com.chh.autosense.unit;

import com.chh.autosense.common.RequestLogFilter;
import com.chh.autosense.support.LogCaptureSupport;
import com.chh.autosense.utils.LogContextUtils;
import com.chh.autosense.utils.LogSanitizer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.ConfigurationFactory;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class LoggingInfrastructureTest {
    @Test void providerAndCaptureRestoreAndSensitiveChains() {
        assertThat(LoggerFactory.getILoggerFactory().getClass().getName())
                .isEqualTo("org.apache.logging.slf4j.Log4jLoggerFactory");
        assertThat(LogManager.getContext(false)).isInstanceOf(LoggerContext.class);
        var original = ((LoggerContext) LogManager.getContext(false)).getConfiguration().getLoggers().get("com.chh.autosense");
        RuntimeException source = new RuntimeException("secret-prompt\nFORGED", new IllegalArgumentException("secret-key"));
        source.addSuppressed(new IllegalStateException("secret-token"));
        try (var logs = new LogCaptureSupport()) {
            LoggerFactory.getLogger("com.chh.autosense.test").error("Request failed: errorCode=INTERNAL_ERROR",
                    LogSanitizer.diagnostic(source));
            assertThat(logs.rendered()).contains("java.lang.RuntimeException", "java.lang.IllegalArgumentException",
                    "java.lang.IllegalStateException").doesNotContain("secret-", "FORGED");
            assertThat(logs.events()).hasSize(1);
        }
        assertThat(((LoggerContext) LogManager.getContext(false)).getConfiguration().getLoggers().get("com.chh.autosense"))
                .isSameAs(original);
        assertThat(LogSanitizer.label("line\n\r\t\u0000value")).isEqualTo("line____value");
        assertThat(LogSanitizer.label("x".repeat(300))).hasSize(128);
    }

    @Test void scopesCopyWhitelistAndRestoreReusedThreads() throws Exception {
        MDC.put("outside", "untouched");
        MDC.put("userId", "9");
        try {
            var snapshot = LogContextUtils.snapshot();
            assertThat(snapshot).containsExactlyEntriesOf(Map.of("userId", "9"));
            assertThatThrownBy(() -> snapshot.put("userId", "11")).isInstanceOf(UnsupportedOperationException.class);
            try (var ignored = LogContextUtils.install(Map.of("userId", "10", "token", "secret", "sessionId", "\nBAD"))) {
                assertThat(MDC.getCopyOfContextMap()).containsExactlyEntriesOf(Map.of("userId", "10"));
                try (var empty = LogContextUtils.install(Map.of())) { assertThat(MDC.get("userId")).isNull(); }
                assertThat(MDC.get("userId")).isEqualTo("10");
            }
            assertThat(MDC.get("outside")).isEqualTo("untouched");
            assertThat(MDC.get("userId")).isEqualTo("9");
        } finally { MDC.clear(); }
    }

    @Test void requestIdsAreServerGeneratedAndReused() throws Exception {
        RequestLogFilter filter = new RequestLogFilter();
        var request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "untrusted");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> assertThat(MDC.get("requestId"))
                .isEqualTo(req.getAttribute(RequestLogFilter.REQUEST_ID)).isNotEqualTo("untrusted"));
        Object first = request.getAttribute(RequestLogFilter.REQUEST_ID);
        filter.doFilter(request, response, (req, res) -> assertThat(MDC.get("requestId")).isEqualTo(first));
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test void consolePatternOverrideIsUsedByTheRealConfiguration() throws Exception {
        String previous = System.getProperty("CONSOLE_LOG_PATTERN");
        System.setProperty("CONSOLE_LOG_PATTERN", "override level=%p %m%n");
        try (LoggerContext isolated = new LoggerContext("logging-config-test")) {
            var uri = getClass().getResource("/log4j2-spring.xml").toURI();
            var config = ConfigurationFactory.getInstance().getConfiguration(isolated, "test", uri);
            isolated.start(config);
            assertThat(config.getAppender("Console").getLayout().toString()).contains("override level=%p");
            assertThat(config.getRootLogger().getLevel().name()).isEqualTo("INFO");
        } finally {
            if (previous == null) System.clearProperty("CONSOLE_LOG_PATTERN");
            else System.setProperty("CONSOLE_LOG_PATTERN", previous);
        }
    }

    @Test void defaultPatternsHideAbsentContextAndGroupConversationFields() throws Exception {
        String previous = System.getProperty("CONSOLE_LOG_PATTERN");
        try {
            // Cover the XML fallback and the pattern exported by Spring Boot from application.yaml.
            var yaml = new org.springframework.beans.factory.config.YamlPropertiesFactoryBean();
            yaml.setResources(new org.springframework.core.io.ClassPathResource("application.yaml"));
            var environment = new org.springframework.core.env.StandardEnvironment();
            environment.getPropertySources().addFirst(new org.springframework.core.env.PropertiesPropertySource(
                    "testYaml", yaml.getObject()));
            String bootPattern = environment.getProperty("logging.pattern.console");
            for (boolean boot : new boolean[]{false, true}) {
                if (boot) System.setProperty("CONSOLE_LOG_PATTERN", bootPattern);
                else System.clearProperty("CONSOLE_LOG_PATTERN");
                try (LoggerContext isolated = new LoggerContext("context-layout-test")) {
                    var uri = getClass().getResource("/log4j2-spring.xml").toURI();
                    var config = ConfigurationFactory.getInstance().getConfiguration(isolated, "test", uri);
                    isolated.start(config);
                    var layout = config.getAppender("Console").getLayout();
                    var event = org.apache.logging.log4j.core.impl.Log4jLogEvent.newBuilder()
                            .setLoggerName("com.chh.autosense.test")
                            .setLevel(org.apache.logging.log4j.Level.INFO).setThreadName("main")
                            .setMessage(new org.apache.logging.log4j.message.SimpleMessage("Application started"))
                            .setContextData(new org.apache.logging.log4j.util.SortedArrayStringMap()).build();
                    String startup = layout.toSerializable(event).toString();
                    assertThat(startup).contains("thread=main Application started")
                            .doesNotContain("requestId=", "userId=", "sessionId=", "messageId=", "round=", "deviceId=", "[]");
                    String requestId = "2529b69a-6cb0-4a87-9d68-4bf329804673";
                    var context = new org.apache.logging.log4j.util.SortedArrayStringMap(Map.of(
                            "requestId", requestId, "userId", "1", "sessionId", "9", "messageId", "41", "round", "3"));
                    var conversation = new org.apache.logging.log4j.core.impl.Log4jLogEvent.Builder(event)
                            .setContextData(context).build();
                    assertThat(layout.toSerializable(conversation).toString()).contains(
                            "[requestId=" + requestId + ", userId=1, sessionId=9, messageId=41, round=3, deviceId=] ");
                }
            }
        } finally {
            if (previous == null) System.clearProperty("CONSOLE_LOG_PATTERN");
            else System.setProperty("CONSOLE_LOG_PATTERN", previous);
        }
    }
}
