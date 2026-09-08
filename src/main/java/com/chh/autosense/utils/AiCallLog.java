package com.chh.autosense.utils;

import dev.langchain4j.exception.HttpException;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** One bounded diagnostic per AI invocation; never inspect exception messages or model data. */
@Slf4j
public final class AiCallLog {
    public enum Phase { INPUT_ENCODING, SERVICE_SETUP, MODEL_INVOCATION, STREAM_START, STREAM_RECEIVE, TOKEN_CALLBACK }

    private final String operation;
    private final String callId = UUID.randomUUID().toString();
    private final long started = System.nanoTime();
    private final Map<String, String> context = LogContextUtils.snapshot();
    private final AtomicBoolean finished = new AtomicBoolean();
    private final AtomicLong firstResponseMs = new AtomicLong(-1);
    private volatile Phase phase = Phase.INPUT_ENCODING;

    private AiCallLog(String operation) {
        this.operation = LogSanitizer.label(operation);
        log.info("AI call started: operation={}, callId={}, phase={}", this.operation, callId, phase);
    }

    public static AiCallLog start(String operation) { return new AiCallLog(operation); }

    public void phase(Phase next) {
        phase = next;
        try (var ignored = LogContextUtils.install(context)) {
            if (next == Phase.MODEL_INVOCATION || next == Phase.STREAM_START) {
                log.info("AI invocation started: operation={}, callId={}, phase={}", operation, callId, next);
                return;
            }
            log.debug("AI call phase changed: operation={}, callId={}, phase={}", operation, callId, next);
        }
    }

    public void firstResponse() {
        if (!finished.get() && firstResponseMs.compareAndSet(-1, elapsed())) {
            try (var ignored = LogContextUtils.install(context)) {
                log.debug("AI stream first response received: operation={}, callId={}, elapsedMs={}",
                        operation, callId, firstResponseMs.get());
            }
        }
    }

    public void completed() {
        if (!finished.compareAndSet(false, true)) return;
        try (var ignored = LogContextUtils.install(context)) {
            log.info("AI call completed: operation={}, callId={}, phase={}, elapsedMs={}, firstResponseMs={}",
                    operation, callId, phase, elapsed(), firstResponseMs.get());
        }
    }

    public void failed(Throwable error) { failed(error, phase); }

    public void failed(Throwable error, Phase failedPhase) {
        if (!finished.compareAndSet(false, true)) return;
        List<Throwable> chain = causes(error);
        Integer httpStatus = chain.stream().filter(HttpException.class::isInstance)
                .map(HttpException.class::cast).map(HttpException::statusCode)
                .filter(code -> code >= 100 && code <= 599).findFirst().orElse(null);
        try (var ignored = LogContextUtils.install(context)) {
            log.warn("AI call failed: operation={}, callId={}, phase={}, reasonCode={}, httpStatus={}, "
                            + "elapsedMs={}, firstResponseMs={}, errorType={}, rootCauseType={}",
                    operation, callId, failedPhase, reason(chain, failedPhase, httpStatus), httpStatus,
                    elapsed(), firstResponseMs.get(), type(error),
                    chain.isEmpty() ? "unknown" : type(chain.getLast()));
        }
    }

    private long elapsed() { return (System.nanoTime() - started) / 1_000_000; }

    private static String type(Throwable error) {
        return error == null ? "unknown" : LogSanitizer.label(error.getClass().getName());
    }

    private static List<Throwable> causes(Throwable error) {
        List<Throwable> chain = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = error; current != null && chain.size() < 32 && seen.add(current);
             current = current.getCause()) chain.add(current);
        return chain;
    }

    private static String reason(List<Throwable> chain, Phase phase, Integer status) {
        if (phase == Phase.INPUT_ENCODING) return "INPUT_ENCODING";
        if (phase == Phase.SERVICE_SETUP) return "SERVICE_SETUP";
        if (phase == Phase.TOKEN_CALLBACK) return "TOKEN_CALLBACK";
        if (chain.stream().anyMatch(e -> e instanceof SocketTimeoutException || e instanceof HttpTimeoutException
                || e instanceof TimeoutException || e instanceof dev.langchain4j.exception.TimeoutException)) return "TIMEOUT";
        if (chain.stream().anyMatch(e -> e instanceof UnknownHostException
                || e instanceof UnresolvedAddressException)) return "DNS";
        if (chain.stream().anyMatch(SSLException.class::isInstance)) return "TLS";
        if (chain.stream().anyMatch(ConnectException.class::isInstance)) return "CONNECTION";
        if (status != null) {
            if (status == 401 || status == 403) return "PROVIDER_AUTH";
            if (status == 429) return "PROVIDER_RATE_LIMIT";
            if (status >= 500) return "PROVIDER_SERVER";
            return "PROVIDER_HTTP";
        }
        if (chain.stream().anyMatch(dev.langchain4j.exception.AuthenticationException.class::isInstance)) return "PROVIDER_AUTH";
        if (chain.stream().anyMatch(dev.langchain4j.exception.RateLimitException.class::isInstance)) return "PROVIDER_RATE_LIMIT";
        if (chain.stream().anyMatch(dev.langchain4j.service.IllegalConfigurationException.class::isInstance)) return "PROMPT_CONFIGURATION";
        if (chain.stream().anyMatch(e -> e.getClass().getName().startsWith("dev.langchain4j.service.output")
                || e.getClass().getName().startsWith("com.fasterxml.jackson"))) return "MODEL_OUTPUT_UNPARSEABLE";
        if (chain.stream().anyMatch(IOException.class::isInstance)) return "NETWORK_IO";
        if (chain.stream().anyMatch(e -> e.getClass().getName().startsWith("dev.langchain4j.exception."))) return "PROVIDER_ERROR";
        return "AI_SERVICE_ERROR";
    }
}
