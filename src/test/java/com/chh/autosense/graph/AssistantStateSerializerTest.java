package com.chh.autosense.graph;

import com.chh.autosense.graph.checkpoint.AssistantStateSerializer;
import com.chh.autosense.graph.state.AssistantState;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class AssistantStateSerializerTest {
    @Test void previousGraphRemainsReadableButCannotResumeRemovedNodes() throws Exception {
        var serializer = new AssistantStateSerializer();
        String previous = serializer.encode(AssistantState.initial(new AssistantState.RequestContext("v3", 1, 1, "question")))
                .replace("assistant-v4", "assistant-v3");
        assertThat(new AssistantState(serializer.decodeHistory(previous)).workflow().graphVersion()).isEqualTo("assistant-v3");
        assertThatThrownBy(() -> serializer.decode(previous)).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> AssistantStateSerializer.requireExecutable(3, "assistant-v3"))
                .isInstanceOf(com.chh.autosense.exception.ApiException.class);
    }
    @Test void versionTwoSnapshotAndTargetRemainReadableButCannotResume() throws Exception {
        var serializer = new AssistantStateSerializer();
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var data = new LinkedHashMap<>(AssistantState.initial(new AssistantState.RequestContext("v2", 1, 1, "question")));
        data.put(AssistantState.DEVICE, new AssistantState.DeviceContext(Map.of("deviceRef", 17L, "sn", "SN17"), Map.of(),
                List.of(Map.of("id", 17L, "name", "lamp", "sn", "SN17", "deviceType", "smart_bulb", "deviceModel", "MJDPL01YL", "online", true)),
                Map.of(), Map.of(), true, "2026-09-22T00:00:00Z", "snapshot"));
        var root = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(serializer.encode(data));
        root.put("schemaVersion", 2).put("graphVersion", "assistant-v2");
        ((com.fasterxml.jackson.databind.node.ObjectNode) root.path("state").path("workflowContext"))
                .put("schemaVersion", 2).put("graphVersion", "assistant-v2");
        var device = (com.fasterxml.jackson.databind.node.ObjectNode) root.path("state").path("deviceContext");
        device.set("resolved", device.remove("target"));
        String payload = json.writeValueAsString(root);
        var restored = new AssistantState(serializer.decodeHistory(payload));
        assertThat(restored.<AssistantState.DeviceContext>value(AssistantState.DEVICE).orElseThrow().target().deviceRef()).isEqualTo(17L);
        assertThatThrownBy(() -> serializer.decode(payload)).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> AssistantStateSerializer.requireExecutable(2, "assistant-v2"))
                .isInstanceOf(com.chh.autosense.exception.ApiException.class);
    }
    @Test void legacySnapshotsAreReadableButNeverExecutableOrReencoded() throws Exception {
        var serializer = new AssistantStateSerializer();
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var root = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(serializer.encode(
                AssistantState.initial(new AssistantState.RequestContext("legacy", 1, 1, "question"))));
        root.put("schemaVersion", 1).put("graphVersion", "assistant-v1");
        ((com.fasterxml.jackson.databind.node.ObjectNode) root.path("state").path("workflowContext"))
                .put("schemaVersion", 1).put("graphVersion", "assistant-v1");
        ((com.fasterxml.jackson.databind.node.ObjectNode) root.path("state").path("deviceContext"))
                .retain("snapshots").set("resolved", json.createObjectNode());
        String legacy = json.writeValueAsString(root);
        var restored = new AssistantState(serializer.decodeHistory(legacy));
        assertThat(restored.workflow().schemaVersion()).isEqualTo(1);
        assertThat(restored.<AssistantState.DeviceContext>value(AssistantState.DEVICE).orElseThrow().initialized()).isFalse();
        assertThatThrownBy(() -> serializer.decode(legacy)).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> serializer.encode(restored.data())).isInstanceOf(java.io.IOException.class);
    }
    @Test void roundTripsTypedContextsAndMessagesWithoutJavaObjectDeserialization() throws Exception {
        var serializer = new AssistantStateSerializer();
        var data = new LinkedHashMap<>(AssistantState.initial(new AssistantState.RequestContext("request", 7, 1, "original")));
        data.put(AssistantState.MESSAGES, List.of(new AssistantState.Message(91, "USER", "original")));
        String json = serializer.encode(data);
        assertThat(json).contains("\"schemaVersion\":3").doesNotContain("@class", "java.", "com.chh");
        var restored = new AssistantState(serializer.decode(json));
        assertThat(restored.request().conversationId()).isEqualTo(7);
        assertThat(restored.messages()).containsExactly(new AssistantState.Message(91, "USER", "original"));
        assertThat(serializer.dataFromBytes(serializer.dataToBytes(data))).isEqualTo(data);
    }

    @Test void rejectsUnknownVersionsMissingContextsAndUnexpectedTypedFields() throws Exception {
        var serializer = new AssistantStateSerializer();
        String valid = serializer.encode(AssistantState.initial(new AssistantState.RequestContext("request", 7, 1, "original")));
        assertThatThrownBy(() -> serializer.decode(valid.replace("\"schemaVersion\":3", "\"schemaVersion\":99")))
                .isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> serializer.decode(valid.replace("assistant-v4", "unrecognized-v2"))).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> serializer.decode("{\"schemaVersion\":3,\"graphVersion\":\"assistant-v3\",\"state\":{}}"))
                .isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> serializer.decode(valid.replace("\"userMessage\":", "\"@class\":\"java.lang.Runtime\",\"userMessage\":")))
                .isInstanceOf(java.io.IOException.class);
    }
}
