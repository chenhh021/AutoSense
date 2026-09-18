package com.chh.autosense.graph;

import com.chh.autosense.graph.checkpoint.AssistantStateSerializer;
import com.chh.autosense.graph.state.AssistantState;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class AssistantStateSerializerTest {
    @Test void roundTripsTypedContextsAndMessagesWithoutJavaObjectDeserialization() throws Exception {
        var serializer = new AssistantStateSerializer();
        var data = new LinkedHashMap<>(AssistantState.initial(new AssistantState.RequestContext("request", 7, 1, "original")));
        data.put(AssistantState.MESSAGES, List.of(new AssistantState.Message(91, "USER", "original")));
        String json = serializer.encode(data);
        assertThat(json).contains("\"schemaVersion\":1").doesNotContain("@class", "java.", "com.chh");
        var restored = new AssistantState(serializer.decode(json));
        assertThat(restored.request().conversationId()).isEqualTo(7);
        assertThat(restored.messages()).containsExactly(new AssistantState.Message(91, "USER", "original"));
        assertThat(serializer.dataFromBytes(serializer.dataToBytes(data))).isEqualTo(data);
    }

    @Test void rejectsUnknownVersionsMissingContextsAndUnexpectedTypedFields() throws Exception {
        var serializer = new AssistantStateSerializer();
        String valid = serializer.encode(AssistantState.initial(new AssistantState.RequestContext("request", 7, 1, "original")));
        assertThatThrownBy(() -> serializer.decode(valid.replace("\"schemaVersion\":1", "\"schemaVersion\":99")))
                .isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> serializer.decode(valid.replace("assistant-v1", "unrecognized-v2"))).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> serializer.decode("{\"schemaVersion\":1,\"graphVersion\":\"assistant-v1\",\"state\":{}}"))
                .isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> serializer.decode(valid.replace("\"userMessage\":", "\"@class\":\"java.lang.Runtime\",\"userMessage\":")))
                .isInstanceOf(java.io.IOException.class);
    }
}
