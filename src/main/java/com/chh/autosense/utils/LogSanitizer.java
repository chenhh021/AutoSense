package com.chh.autosense.utils;

import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Never retain raw exceptions: provider messages can contain prompts and credentials. */
public final class LogSanitizer {
    private LogSanitizer() { }

    public static String label(String value) {
        String clean = value == null ? "unknown" : value.replaceAll("[\\p{Cntrl}\\p{Cf}]", "_");
        return clean.substring(0, Math.min(clean.length(), 128));
    }

    public static Throwable diagnostic(Throwable error) {
        return copy(error, Collections.newSetFromMap(new IdentityHashMap<>()), 0);
    }

    private static Throwable copy(Throwable source, Set<Throwable> seen, int depth) {
        if (source == null || depth >= 8 || !seen.add(source)) {
            return new SafeDiagnostic("Omitted diagnostic chain");
        }
        SafeDiagnostic safe = new SafeDiagnostic(source.getClass().getName());
        safe.setStackTrace(Arrays.stream(source.getStackTrace()).limit(32)
                .filter(frame -> frame.getClassName().startsWith("com.chh.autosense.")
                        || frame.getClassName().startsWith("java.")
                        || frame.getClassName().startsWith("org.springframework.")
                        || frame.getClassName().startsWith("dev.langchain4j."))
                .map(frame -> new StackTraceElement(label(frame.getClassName()),
                        label(frame.getMethodName()), null, frame.getLineNumber())).toArray(StackTraceElement[]::new));
        if (source.getCause() != null) safe.initCause(copy(source.getCause(), seen, depth + 1));
        Arrays.stream(source.getSuppressed()).limit(4)
                .forEach(suppressed -> safe.addSuppressed(copy(suppressed, seen, depth + 1)));
        return safe;
    }

    private static final class SafeDiagnostic extends RuntimeException {
        private SafeDiagnostic(String type) {
            super(type);
            setStackTrace(new StackTraceElement[0]);
        }
    }
}
