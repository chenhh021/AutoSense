package com.chh.autosense.core.session.memory;

import java.util.List;

public record ConversationHistorySnapshot(long sessionId, long beforeMessageId, List<Entry> messages) {
    public ConversationHistorySnapshot { messages = List.copyOf(messages); }
    public record Entry(long id, String role, String content) { }
}
