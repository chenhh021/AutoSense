package com.chh.autosense.unit;

import com.chh.autosense.support.LogCaptureSupport;
import com.chh.autosense.utils.AiCallLog;
import com.fasterxml.jackson.core.JsonParseException;
import dev.langchain4j.exception.HttpException;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import javax.net.ssl.SSLException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AiCallLogTest {
    @Test void unwrapsFailuresWithoutInspectingOrLoggingSensitiveMessages() {
        Map<String, Throwable> failures = Map.of(
                "DNS", new UnknownHostException("secret-host"),
                "TLS", new SSLException("secret-url"),
                "CONNECTION", new ConnectException("secret-endpoint"),
                "TIMEOUT", new SocketTimeoutException("secret-input"),
                "PROVIDER_AUTH", new HttpException(401, "secret-key"),
                "PROVIDER_RATE_LIMIT", new HttpException(429, "secret-body"),
                "PROVIDER_SERVER", new HttpException(503, "secret-provider-body"),
                "PROVIDER_HTTP", new HttpException(404, "secret-path"),
                "MODEL_OUTPUT_UNPARSEABLE", new JsonParseException(null, "secret-model-output"));
        try (var logs = new LogCaptureSupport()) {
            failures.forEach((reason, cause) -> {
                logs.clear();
                var call = AiCallLog.start("intent-classify");
                call.phase(AiCallLog.Phase.MODEL_INVOCATION);
                call.failed(new IllegalStateException("secret-wrapper", cause));
                call.completed();
                call.failed(cause);
                assertThat(logs.rendered()).contains("reasonCode=" + reason,
                                "rootCauseType=" + cause.getClass().getName(), "callId=", "elapsedMs=")
                        .doesNotContain("secret-", "AI call completed");
                assertThat(logs.events()).filteredOn(e -> e.getMessage().getFormattedMessage()
                        .startsWith("AI call failed:")).hasSize(1);
            });
        }
    }

    @Test void callbackRestoresMdcAndRetainsOriginalRequestIdentity() throws Exception {
        try (var logs = new LogCaptureSupport(); var worker = Executors.newSingleThreadExecutor()) {
            MDC.put("sessionId", "10");
            var call = AiCallLog.start("direct-answer");
            MDC.clear();
            worker.submit(() -> {
                MDC.put("sessionId", "99");
                call.phase(AiCallLog.Phase.STREAM_RECEIVE);
                call.firstResponse();
                call.completed();
                assertThat(MDC.get("sessionId")).isEqualTo("99");
                MDC.clear();
            }).get(3, TimeUnit.SECONDS);
            assertThat(logs.events()).allSatisfy(e ->
                    assertThat(e.getContextData().getValue("sessionId").toString()).isEqualTo("10"));
        } finally { MDC.clear(); }
    }

    @Test void boundsCyclicCauseChainsAndSeparatesLocalFailures() {
        var first = new IllegalStateException("secret-first");
        var second = new IllegalArgumentException("secret-second", first);
        first.initCause(second);
        try (var logs = new LogCaptureSupport()) {
            AiCallLog.start("intent-classify").failed(first);
            assertThat(logs.rendered()).contains("reasonCode=INPUT_ENCODING").doesNotContain("secret-");
        }
    }
}
