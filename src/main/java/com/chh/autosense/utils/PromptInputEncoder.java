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
    private final com.fasterxml.jackson.databind.ObjectReader knowledgeReader =
            new ObjectMapper().readerFor(com.chh.autosense.domain.dto.KnowledgeAnswerRequest.class)
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

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
        return encode(visibleHistory(snapshot));
    }

    public List<Map<String, String>> visibleHistory(ConversationHistorySnapshot snapshot) {
        Objects.requireNonNull(snapshot, "history snapshot is required");
        return snapshot.messages().stream()
                .filter(entry -> entry.id() < snapshot.beforeMessageId())
                .skip(Math.max(0, snapshot.messages().stream().filter(entry -> entry.id() < snapshot.beforeMessageId()).count() - 20))
                .map(entry -> Map.of("role", entry.role(), "content", entry.content()))
                .toList();
    }

    /** Candidate symptom as a JSON string, or the JSON literal null when absent. */
    public String symptom(String symptom) {
        return symptom == null ? "null" : encode(symptom);
    }

    /** Server-side diagnostics as a JSON object; a null map is encoded as {}. */
    public String diagnostics(Map<String, ?> diagnostics) {
        return diagnostics == null ? "{}" : encode(diagnostics);
    }

    public String deviceContext(com.chh.autosense.graph.state.AssistantState.DeviceContext context) {
        if (!context.initialized()) return encode(Map.of("initialized", false));
        return encode(Map.of("devices", context.planningDevices(), "statusMetadata", context.statusMetadata(),
                "basicMetadata", context.basicMetadata(), "observedAt", context.initializedAt()));
    }

    private String encode(Object value) {
        rejectRaw(value);
        try {
            return writer.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Prompt input encoding failed");
        }
    }

    public String knowledgeRequest(com.chh.autosense.domain.dto.KnowledgeAnswerRequest request) {
        return encode(Objects.requireNonNull(request));
    }

    public com.chh.autosense.domain.dto.KnowledgeAnswerRequest readKnowledgeRequest(String request) {
        try {
            return Objects.requireNonNull(knowledgeReader.readValue(request));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid knowledge request envelope");
        }
    }

    public String answerContext(com.chh.autosense.domain.dto.KnowledgeDirectAnswerContext context) {
        return encode(Objects.requireNonNull(context));
    }

    public String knowledgeCatalog(com.chh.autosense.ai.rag.KnowledgeEmbeddingStore.Catalog catalog) {
        return encode(catalog);
    }

    public String knowledgeEvidence(com.chh.autosense.domain.dto.KnowledgeAnswerRequest request,
                                    List<dev.langchain4j.rag.content.Content> contents) {
        var evidence = contents.stream().map(content -> {
            var segment = content.textSegment();
            Map<String, Object> projection = new java.util.LinkedHashMap<>();
            for (String key : List.of("sourceId", "sourceName", "documentHash", "index", "deviceType",
                    "brand", "model", "knowledgeKind")) projection.put(key, segment.metadata().getString(key));
            projection.put("text", segment.text());
            projection.put("score", content.metadata().get(dev.langchain4j.rag.content.ContentMetadata.SCORE));
            return projection;
        }).toList();
        return encode(Map.of("history", request.history(), "text", request.text(), "queryText", request.queryText(),
                "scope", request.scope(), "evidence", evidence));
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
