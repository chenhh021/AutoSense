package com.chh.autosense.exception;

/** A safe diagnostic: never attaches an SDK/resource exception containing document text or secrets. */
public final class KnowledgeInitializationException extends IllegalStateException {
    public KnowledgeInitializationException(String stage, String reason, String sourceId) {
        super("Knowledge initialization failed: stage=" + safeLabel(stage) + ", reasonCode=" + safeLabel(reason)
                + ", sourceId=" + safeSource(sourceId));
    }

    private static String safeLabel(String label) {
        return label != null && label.matches("[A-Z_]+") ? label : "UNKNOWN";
    }

    private static String safeSource(String source) {
        return source != null && source.length() <= 300
                && source.matches("document/[a-z][a-z0-9_]*/[A-Za-z0-9_]+-[A-Za-z0-9][A-Za-z0-9._-]*/(general|troubleshot)\\.md")
                ? source : "unknown";
    }
}
