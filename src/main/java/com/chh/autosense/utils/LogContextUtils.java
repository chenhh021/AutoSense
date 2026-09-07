package com.chh.autosense.utils;

import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Explicit context scopes for HTTP, executor, SDK and timer boundaries. */
public final class LogContextUtils {
    private static final Set<String> KEYS = Set.of(
            "requestId", "userId", "sessionId", "messageId", "round", "deviceId");

    private LogContextUtils() { }

    public static Map<String, String> snapshot() {
        return whitelist(MDC.getCopyOfContextMap());
    }

    public static Map<String, String> with(Map<String, String> context, String key, Object value) {
        Map<String, String> next = new HashMap<>(whitelist(context));
        if (KEYS.contains(key) && value != null) {
            next.put(key, String.valueOf(value));
        }
        return whitelist(next);
    }

    private static Map<String, String> whitelist(Map<String, String> source) {
        Map<String, String> safe = new HashMap<>();
        if (source != null) {
            source.forEach((key, value) -> {
                if (KEYS.contains(key) && value != null && value.length() <= 64
                        && (key.equals("requestId") ? value.matches("[a-fA-F0-9-]{32,36}")
                        : value.matches("[1-9][0-9]{0,18}"))) {
                    safe.put(key, value);
                }
            });
        }
        return Map.copyOf(safe);
    }

    public static Scope install(Map<String, String> context) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        Map<String, String> next = whitelist(context);
        MDC.clear();
        next.forEach(MDC::put);
        return () -> {
            MDC.clear();
            if (previous != null) MDC.setContextMap(previous);
        };
    }

    public static Runnable wrap(Map<String, String> context, Runnable action) {
        Map<String, String> copy = whitelist(context);
        return () -> { try (Scope ignored = install(copy)) { action.run(); } };
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override void close();
    }
}
