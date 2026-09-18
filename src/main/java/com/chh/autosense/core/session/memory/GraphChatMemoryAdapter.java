package com.chh.autosense.core.session.memory;

import com.chh.autosense.graph.state.AssistantState;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.data.message.*;
import org.springframework.stereotype.Component;
import java.util.*;

/** A new request-scoped memory, never attached to a cached AI service proxy. */
@Component
public class GraphChatMemoryAdapter {
    public record Input(ConversationHistorySnapshot history, String text, ChatMemory memory) { }
    public Input input(AssistantState state) {
        var visible = state.messages().stream().filter(m -> Set.of("USER", "ASSISTANT").contains(m.role())).toList();
        var current = visible.stream().filter(m -> m.role().equals("USER")).max(Comparator.comparingLong(AssistantState.Message::id)).orElse(null);
        long currentId = current == null ? Long.MAX_VALUE : current.id();
        String text = current == null ? state.request().userMessage() : current.content();
        var prior = visible.stream().filter(m -> m.id() < currentId).sorted(Comparator.comparingLong(AssistantState.Message::id)).toList();
        prior = prior.subList(Math.max(0, prior.size() - 20), prior.size());
        ChatMemory memory = MessageWindowChatMemory.withMaxMessages(21);
        prior.forEach(m -> memory.add(m.role().equals("USER") ? UserMessage.from(m.content()) : AiMessage.from(m.content())));
        memory.add(UserMessage.from(text));
        var history = new ConversationHistorySnapshot(state.request().conversationId(), currentId,
                prior.stream().map(m -> new ConversationHistorySnapshot.Entry(m.id(), m.role(), m.content())).toList());
        return new Input(history, text, memory);
    }
}
