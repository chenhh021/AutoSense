package com.chh.autosense.core.session.memory;

import com.chh.autosense.core.session.SessionProcessingService.Accepted;
import com.chh.autosense.mapper.ChatMessageMapper;
import com.chh.autosense.mapper.RepairSessionMapper;
import com.chh.autosense.exception.ApiException;
import com.chh.autosense.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.Objects;

@Service
public class ConversationHistoryService {
    private final ChatMessageMapper messages;
    private final RepairSessionMapper sessions;

    public ConversationHistoryService(ChatMessageMapper messages, RepairSessionMapper sessions) {
        this.messages = messages;
        this.sessions = sessions;
    }

    @Transactional(readOnly = true)
    public ConversationHistorySnapshot snapshot(Accepted accepted) {
        var session = sessions.selectOneById(accepted.sessionId());
        var current = messages.selectOneById(accepted.messageId());
        if (session == null || !Objects.equals(session.getUserId(), accepted.userId())
                || current == null || !Objects.equals(current.getSessionId(), accepted.sessionId())
                || !"USER".equals(current.getRole())) {
            throw new ApiException(ErrorCode.SESSION_NOT_FOUND, "会话不存在", accepted.sessionId());
        }
        var entries = messages.historyBefore(accepted.sessionId(), accepted.messageId()).stream()
                .map(m -> new ConversationHistorySnapshot.Entry(m.getId(), m.getRole(), m.getContent()))
                .sorted(Comparator.comparingLong(ConversationHistorySnapshot.Entry::id)).toList();
        return new ConversationHistorySnapshot(accepted.sessionId(), accepted.messageId(), entries);
    }
}
