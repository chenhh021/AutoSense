package com.chh.autosense.graph.checkpoint;

import com.chh.autosense.graph.state.AssistantState;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.bsc.langgraph4j.serializer.StateSerializer;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.chh.autosense.graph.state.AssistantState.*;

/** Fixed-schema JSON only. Never calls readObject or enables polymorphic class loading. */
public final class AssistantStateSerializer extends StateSerializer<AssistantState> {
    public static final int SCHEMA_VERSION = 3;
    public static final String GRAPH_VERSION = "assistant-v4";
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final Map<String, Class<?>> TYPES = Map.of(
            REQUEST, RequestContext.class, PLAN, PlanContext.class, WORKFLOW, WorkflowContext.class,
            DEVICE, DeviceContext.class, DIAGNOSIS, DiagnosisContext.class, CONTROL, ControlContext.class,
            RETRY, RetryContext.class, AUDIT, AuditContext.class, OUTPUT, OutputContext.class);
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    public AssistantStateSerializer() { super(AssistantState::new); }

    public String encode(Map<String, Object> data) throws IOException {
        try {
            var state = new AssistantState(data);
            checkVersion(state.workflow().schemaVersion(), state.workflow().graphVersion());
            String encoded = json.writeValueAsString(Map.of("schemaVersion", SCHEMA_VERSION,
                    "graphVersion", GRAPH_VERSION, "state", state.data()));
            if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new IOException("Checkpoint exceeds size limit");
            return encoded;
        } catch (IllegalArgumentException e) { throw new IOException("Invalid checkpoint state", e); }
    }

    public Map<String, Object> decode(String payload) throws IOException { return decode(payload, false); }
    public Map<String, Object> decodeHistory(String payload) throws IOException { return decode(payload, true); }

    private Map<String, Object> decode(String payload, boolean history) throws IOException {
        if (payload == null || payload.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new IOException("Invalid checkpoint size");
        try {
            JsonNode root = json.readTree(payload);
            if (root == null || !root.isObject() || root.size() != 3 || !root.path("schemaVersion").isInt())
                throw new IOException("Invalid checkpoint envelope");
            int version = root.path("schemaVersion").intValue();
            boolean legacy = history && (version == 1 || version == 2 || version == 3)
                    && ("assistant-v" + version).equals(root.path("graphVersion").textValue());
            if (!legacy) checkVersion(root.path("schemaVersion").intValue(), root.path("graphVersion").textValue());
            var raw = root.path("state");
            if (!raw.isObject() || raw.size() != 10 || !raw.hasNonNull(MESSAGES)) throw new IOException("Incomplete checkpoint state");
            var device = raw.path(DEVICE);
            if (!device.isObject() || device.size() != (legacy && version == 1 ? 2 : 8))
                throw new IOException("Incomplete device snapshot context");
            if (legacy && version < 3) {
                var object = (com.fasterxml.jackson.databind.node.ObjectNode) device;
                JsonNode resolved = object.remove("resolved");
                if (resolved == null || !resolved.isObject()) throw new IOException("Missing legacy target");
                object.set("target", resolved.isEmpty() ? json.nullNode() : resolved);
            }
            var data = new LinkedHashMap<String, Object>();
            for (var entry : TYPES.entrySet()) {
                if (!raw.hasNonNull(entry.getKey())) throw new IOException("Missing checkpoint context");
                data.put(entry.getKey(), json.treeToValue(raw.get(entry.getKey()), entry.getValue()));
            }
            data.put(MESSAGES, json.convertValue(raw.get(MESSAGES), json.getTypeFactory().constructCollectionType(List.class, Message.class)));
            var state = new AssistantState(data);
            if (legacy) {
                if (state.workflow().schemaVersion() != version || !("assistant-v" + version).equals(state.workflow().graphVersion()))
                    throw new IOException("Inconsistent legacy version");
            } else checkVersion(state.workflow().schemaVersion(), state.workflow().graphVersion());
            return state.data();
        } catch (IllegalArgumentException e) { throw new IOException("Invalid checkpoint state", e); }
    }

    public static void requireExecutable(Integer schema, String graph) {
        if (!Integer.valueOf(SCHEMA_VERSION).equals(schema) || !GRAPH_VERSION.equals(graph))
            throw new com.chh.autosense.exception.ApiException(
                    com.chh.autosense.exception.ErrorCode.INCOMPATIBLE_WORKFLOW_VERSION,
                    "该流程由旧版本创建，请新建会话重新发起请求。历史记录仍可查看。");
    }

    private void checkVersion(int schema, String graph) throws IOException {
        if (schema != SCHEMA_VERSION || !GRAPH_VERSION.equals(graph)) throw new IOException("Unsupported checkpoint version");
    }

    @Override public void writeData(Map<String, Object> data, ObjectOutput output) throws IOException {
        byte[] bytes = encode(data).getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length); output.write(bytes);
    }
    @Override public Map<String, Object> readData(ObjectInput input) throws IOException {
        int length = input.readInt();
        if (length < 1 || length > MAX_BYTES) throw new IOException("Invalid checkpoint size");
        byte[] bytes = new byte[length]; input.readFully(bytes);
        return decode(new String(bytes, StandardCharsets.UTF_8));
    }
}
