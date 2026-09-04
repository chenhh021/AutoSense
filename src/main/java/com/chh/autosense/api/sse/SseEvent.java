package com.chh.autosense.api.sse;

import java.util.Map;

/**
 * SSE 事件模型(R18,FR-021):五类事件。
 */
public record SseEvent(String event, Object data) {

    public static SseEvent token(String text) {
        return new SseEvent("token", Map.of("text", text));
    }

    public static SseEvent status(long sessionId, String status) {
        return new SseEvent("status", Map.of("sessionId", sessionId, "status", status));
    }

    public static SseEvent awaiting(long sessionId, String prompt) {
        return new SseEvent("awaiting", Map.of("sessionId", sessionId, "prompt", prompt));
    }

    public static SseEvent conclusion(Object conclusion) {
        return new SseEvent("conclusion", conclusion);
    }

    public static SseEvent error(String code, String message, Long sessionId) {
        return new SseEvent("error",
                Map.of("code", code, "message", message, "sessionId",
                        sessionId == null ? -1 : sessionId));
    }
}
