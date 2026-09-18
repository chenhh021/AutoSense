package com.chh.autosense.graph;

import com.chh.autosense.core.session.memory.GraphChatMemoryAdapter;
import com.chh.autosense.graph.state.AssistantState;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class GraphChatMemoryTest {
    @Test void currentInputIsIncludedOnceAndOtherConversationNeverLeaks() {
        var initial = new LinkedHashMap<>(AssistantState.initial(new AssistantState.RequestContext("request", 1, 1, "original")));
        var messages = new ArrayList<AssistantState.Message>();
        for (long id = 1; id <= 30; id++) messages.add(new AssistantState.Message(id, id % 2 == 0 ? "ASSISTANT" : "USER", "message-" + id));
        messages.add(new AssistantState.Message(31, "USER", "clarification")); initial.put(AssistantState.MESSAGES, messages);
        var first = new GraphChatMemoryAdapter().input(new AssistantState(initial));
        assertThat(first.history().messages()).hasSize(20);
        assertThat(first.text()).isEqualTo("clarification");
        assertThat(first.memory().messages()).hasSize(21);
        assertThat(first.history().messages()).noneMatch(m -> m.content().equals("clarification"));
        var other = new AssistantState(AssistantState.initial(new AssistantState.RequestContext("another", 2, 1, "separate")));
        var second = new GraphChatMemoryAdapter().input(other);
        assertThat(second.history().messages()).isEmpty(); assertThat(second.text()).isEqualTo("separate");
        assertThat(first.text()).isEqualTo("clarification");
    }
}
