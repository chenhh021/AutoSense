package com.chh.autosense.ai.factory;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

/**
 * Separates model transport failures (AI service unavailable) from structured-output
 * parsing failures (clarification), per the routing contract. Never inspects or retains
 * provider message bodies.
 */
public final class AiFailureMapping {

    private AiFailureMapping() { }

    /** Connection, timeout, authentication or provider-side HTTP failures. */
    public static boolean isTransportFailure(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof TimeoutException || current instanceof IOException) {
                return true;
            }
            String name = current.getClass().getName();
            if (name.startsWith("dev.langchain4j.exception.")) {
                return true;
            }
        }
        return false;
    }

    /** Structured output could not be produced or parsed into the declared record. */
    public static boolean isStructureFailure(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String name = current.getClass().getName();
            if (name.startsWith("com.fasterxml.jackson")
                    || name.startsWith("dev.langchain4j.service.output")) {
                return true;
            }
        }
        return false;
    }
}
