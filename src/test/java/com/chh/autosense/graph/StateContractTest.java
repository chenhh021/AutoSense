package com.chh.autosense.graph;

import com.chh.autosense.graph.state.AssistantState;
import com.chh.autosense.graph.state.StateData;
import org.bsc.langgraph4j.state.AgentState;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.chh.autosense.graph.state.AssistantState.*;
import static org.assertj.core.api.Assertions.*;

class StateContractTest {
    @Test void replacingOneContextPreservesOtherContextsWithoutDeepMerge() {
        var initial = AssistantState.initial(new RequestContext("request-1", 11, 1, "question"));
        var first = AgentState.updateState(initial,
                Map.of(DEVICE, new DeviceContext(Map.of("name", "lamp"), Map.of("s1", 10))), schema());
        var replaced = AgentState.updateState(first,
                Map.of(DEVICE, new DeviceContext(Map.of(), Map.of())), schema());
        assertThat(replaced).hasSize(10);
        assertThat(replaced.get(REQUEST)).isEqualTo(initial.get(REQUEST));
        assertThat(((DeviceContext) replaced.get(DEVICE)).snapshots()).isEmpty();
    }

    @Test void nestedRuntimePayloadIsCopiedAndRuntimeObjectsAreRejected() {
        var list = new ArrayList<>(List.of("first"));
        var source = new HashMap<String, Object>();
        source.put("values", list);
        var context = new DeviceContext(Map.of(), source);
        list.add("second"); source.clear();
        assertThat((List<?>) context.snapshots().get("values")).hasSize(1);
        assertThatThrownBy(() -> context.snapshots().put("x", 1)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> StateData.freeze(Map.of("runtime", new Object())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void messagesAreBoundedAndDeduplicatedByIdRatherThanText() {
        List<Message> input = new ArrayList<>();
        for (int i = 1; i <= 30; i++) input.add(new Message(i, "USER", "same text"));
        input.add(new Message(30, "USER", "updated"));
        var messages = window(input);
        assertThat(messages).hasSize(21);
        assertThat(messages.getFirst().id()).isEqualTo(10);
        assertThat(messages.getLast().content()).isEqualTo("updated");
    }

    @Test void admissionRejectsUntrustedOrMissingIdentity() {
        assertThatThrownBy(() -> initial(new RequestContext("", 11, 1, "question")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> initial(new RequestContext("request-1", 11, 0, "question")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void aDisplayCursorCannotAdvanceIndependentlyOfThePlan() {
        var data = new HashMap<>(initial(new RequestContext("request-1", 11, 1, "question")));
        data.put(WORKFLOW, new WorkflowContext(com.chh.autosense.domain.enums.WorkflowStatus.RUNNING,
                1, new Progress(1, 0, 0, 0), 1, "", "", "", ""));
        assertThatThrownBy(() -> new AssistantState(data)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AssistantState(Map.of(DEVICE, new Object())))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
