package com.chh.autosense.utils;

import com.chh.autosense.core.session.memory.ConversationHistorySnapshot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.util.RawValue;
import com.fasterxml.jackson.core.SerializableString;
import com.fasterxml.jackson.core.io.CharacterEscapes;
import com.fasterxml.jackson.core.io.SerializedString;
import org.springframework.stereotype.Component;

import java.lang.reflect.Array;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure data encoder for AI service template binding (prompt contract §3). Only braces inside
 * ordinary JSON string values and keys are escaped as / so the fixed LangChain4j
 * sequential replacement cannot reinterpret template markers inside data. Structural braces,
 * numbers and booleans stay untouched; the shared HTTP ObjectMapper and persisted text are
 * never modified. RawValue and other raw-serialization bypasses are rejected.
 */
@Component
public class PromptInputEncoder {

    private final ObjectWriter writer;

    public PromptInputEncoder() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.getFactory().setCharacterEscapes(new BraceEscapes());
        this.writer = mapper.writer();
    }

    /** Current user input as a JSON string. Never Java null. */
    public String text(String text) {
        return encode(Objects.requireNonNull(text, "text is required"));
    }

    /** Read-only history projected to a JSON array of {"role","content"}; empty history is []. */
    public String history(ConversationHistorySnapshot snapshot) {
        Objects.requireNonNull(snapshot, "history snapshot is required");
        List<Map<String, String>> projection = snapshot.messages().stream()
                .map(entry -> Map.of("role", entry.role(), "content", entry.content()))
                .toList();
        return encode(projection);
    }

    /** Candidate symptom as a JSON string, or the JSON literal null when absent. */
    public String symptom(String symptom) {
        return symptom == null ? "null" : encode(symptom);
    }

    /** Server-side diagnostics as a JSON object; a null map is encoded as {}. */
    public String diagnostics(Map<String, ?> diagnostics) {
        return diagnostics == null ? "{}" : encode(diagnostics);
    }

    private String encode(Object value) {
        rejectRaw(value);
        try {
            return writer.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Prompt input encoding failed");
        }
    }

    private static void rejectRaw(Object value) {
        if (value instanceof RawValue) {
            throw new IllegalArgumentException("Raw serialization is not allowed in prompt input");
        }
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, entry) -> {
                rejectRaw(key);
                rejectRaw(entry);
            });
        } else if (value instanceof Iterable<?> iterable) {
            iterable.forEach(PromptInputEncoder::rejectRaw);
        } else if (value != null && value.getClass().isArray() && !(value instanceof byte[])) {
            for (int i = 0; i < Array.getLength(value); i++) {
                rejectRaw(Array.get(value, i));
            }
        }
    }

    private static final class BraceEscapes extends CharacterEscapes {
        private static final SerializableString OPEN = new SerializedString("\\u007b");
        private static final SerializableString CLOSE = new SerializedString("\\u007d");
        private final int[] escapes;

        BraceEscapes() {
            escapes = standardAsciiEscapesForJSON();
            escapes['{'] = ESCAPE_CUSTOM;
            escapes['}'] = ESCAPE_CUSTOM;
        }

        @Override
        public int[] getEscapeCodesForAscii() {
            return escapes;
        }

        @Override
        public SerializableString getEscapeSequence(int ch) {
            if (ch == '{') {
                return OPEN;
            }
            return ch == '}' ? CLOSE : null;
        }
    }
}
