package com.chh.autosense.core.session;

import com.chh.autosense.config.AssistantProperties;
import com.chh.autosense.config.LlmProperties;
import com.chh.autosense.core.security.AuthUser;
import com.chh.autosense.domain.dto.ConclusionDto;
import com.chh.autosense.domain.entity.*;
import com.chh.autosense.domain.enums.SessionStatus;
import com.chh.autosense.exception.ApiException;
import com.chh.autosense.exception.ErrorCode;
import com.chh.autosense.mapper.*;
import com.chh.autosense.utils.LogContextUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;

import static com.chh.autosense.domain.enums.SessionStatus.*;

/** All accepted-message writes are short, row-locked transactions. No model or device calls. */
@Service
@Slf4j
public class SessionProcessingService {
    private final RepairSessionMapper sessions;
    private final ProblemReportMapper reports;
    private final ChatMessageMapper messages;
    private final RepairActionLogMapper audit;
    private final DiagnosticSnapshotMapper snapshots;
    private final SessionTransitionLog transitions;
    private final AssistantProperties properties;
    private final ObjectMapper json;

    public SessionProcessingService(RepairSessionMapper sessions, ProblemReportMapper reports,
            ChatMessageMapper messages, RepairActionLogMapper audit, DiagnosticSnapshotMapper snapshots,
            SessionTransitionLog transitions,
            AssistantProperties properties, LlmProperties llm, ObjectMapper json) {
        this.sessions = sessions;
        this.reports = reports;
        this.messages = messages;
        this.audit = audit;
        this.snapshots = snapshots;
        this.transitions = transitions;
        this.properties = properties;
        this.json = json;
        properties.validateModelBudget(llm);
    }

    public record Accepted(long sessionId, long userId, long messageId, long reportId, int round,
            String text, Boolean confirmRepair, LocalDateTime deadline, SessionStatus previousStatus) {
        public Map<String, String> logContext() {
            Map<String, String> context = LogContextUtils.snapshot();
            context = LogContextUtils.with(context, "userId", userId);
            context = LogContextUtils.with(context, "sessionId", sessionId);
            context = LogContextUtils.with(context, "messageId", messageId);
            return LogContextUtils.with(context, "round", round);
        }
    }

    @Transactional
    public Accepted create(AuthUser user, String text) {
        requireUser(user);
        requireText(text, null);
        RepairSession session = new RepairSession();
        session.setUserId(user.userId());
        session.setStatus(CREATED.name());
        sessions.insert(session);
        return admit(session, text, null, true);
    }

    @Transactional
    public Accepted accept(AuthUser user, long sessionId, String text, Boolean confirmRepair) {
        RepairSession session = lockOwned(user, sessionId);
        if (session.getProcessingMessageId() != null) {
            if (!expireLocked(session, session.getProcessingMessageId())) {
                log.warn("Session request rejected: sessionId={}, errorCode=SESSION_BUSY", sessionId);
                throw new ApiException(ErrorCode.SESSION_BUSY, "该会话正在处理上一条消息，请稍后再试。", sessionId);
            }
        }
        SessionStatus status = SessionStatus.valueOf(session.getStatus());
        if (!status.isTerminal() && !status.isAwaitingUser() && status != CREATED) {
            failLegacyLocked(session);
        }
        return admit(session, text, confirmRepair, false);
    }

    private Accepted admit(RepairSession session, String text, Boolean confirmRepair, boolean creating) {
        requireText(text, confirmRepair);
        String visible = text == null || text.isBlank()
                ? (Boolean.TRUE.equals(confirmRepair) ? "确认继续设备操作" : "取消设备操作") : text;
        SessionStatus previous = SessionStatus.valueOf(session.getStatus());
        ProblemReport report = reports.latest(session.getId());
        if (creating || previous.isTerminal() || report == null) {
            preserveConclusion(session);
            int round = report == null ? 1 : report.getRound() + 1;
            report = new ProblemReport();
            report.setSessionId(session.getId());
            report.setRound(round);
            report.setRawText(visible);
            reports.insert(report);
            session.setConclusion(null);
            session.setConclusionType(null);
            session.setConclusionExtra(null);
            session.setDeviceId(null);
        }
        ChatMessage message = saveMessage(session.getId(), "USER", visible);
        LocalDateTime deadline = sessions.databaseNow().plusSeconds(properties.processingTimeoutSeconds());
        session.setProcessingMessageId(message.getId());
        session.setProcessingDeadlineAt(deadline);
        Accepted accepted = new Accepted(session.getId(), session.getUserId(), message.getId(),
                report.getId(), report.getRound(), visible, confirmRepair, deadline, previous);
        try (var ignored = LogContextUtils.install(accepted.logContext())) {
        // Capability-specific waits stay in their state until their handler resolves continuation.
        if (previous == CREATED || previous.isTerminal() || previous == CLARIFYING) {
            transitions.transit(session, previous, ROUTING);
        }
        sessions.update(session, false);
            trace(session.getId(), "request:accepted", "SUCCESS", accepted.messageId(), accepted.round(), null);
            SessionTransitionLog.afterCommit(() -> log.info("Session request accepted: messageId={}, round={}",
                    accepted.messageId(), accepted.round()));
        }
        return accepted;
    }

    @Transactional
    public boolean advance(Accepted accepted, SessionStatus next, String intent) {
        RepairSession session = current(accepted);
        if (session == null) return false;
        if (intent != null) {
            ProblemReport report = reports.selectOneById(accepted.reportId());
            if (report == null || !Objects.equals(report.getSessionId(), accepted.sessionId())) {
                throw new IllegalStateException("Accepted report is unavailable");
            }
            report.setIntent(intent);
            reports.update(report);
            trace(session.getId(), "route:selected", "SUCCESS", accepted.messageId(), accepted.round(), intent);
        }
        SessionStatus from = SessionStatus.valueOf(session.getStatus());
        if (from != next) transitions.transit(session, from, next);
        return true;
    }

    @Transactional
    public boolean finish(Accepted accepted, SessionStatus target, String visible,
                          ConclusionDto conclusion, ErrorCode error) {
        RepairSession session = current(accepted);
        if (session == null) return false;
        if (!target.isTerminal() && !target.isAwaitingUser()) {
            throw new IllegalArgumentException("Finalization requires a waiting or terminal state");
        }
        finishLocked(session, accepted.messageId(), accepted.round(), target, visible, conclusion, error);
        return true;
    }

    @Transactional
    public boolean isCurrent(Accepted accepted) { return current(accepted) != null; }

    @Transactional
    public boolean expire(long sessionId, long messageId) {
        RepairSession session = sessions.lockById(sessionId);
        return session != null && expireLocked(session, messageId);
    }

    @Transactional
    public RepairSession recoverOwned(AuthUser user, long sessionId) {
        RepairSession session = lockOwned(user, sessionId);
        if (session.getProcessingMessageId() != null) {
            expireLocked(session, session.getProcessingMessageId());
        } else {
            SessionStatus status = SessionStatus.valueOf(session.getStatus());
            if (!status.isTerminal() && !status.isAwaitingUser()) failLegacyLocked(session);
        }
        return session;
    }

    /**
     * 删除本人会话:处理中的会话先尝试超时补偿结清,仍在处理则拒绝(不假成功);
     * 同一事务内级联删除消息、上报、快照与审计记录后删除会话行。
     */
    @Transactional
    public void deleteOwned(AuthUser user, long sessionId) {
        RepairSession session = lockOwned(user, sessionId);
        if (session.getProcessingMessageId() != null
                && !expireLocked(session, session.getProcessingMessageId())) {
            log.warn("Session operation rejected: sessionId={}, operation=delete, errorCode=SESSION_BUSY",
                    sessionId);
            throw new ApiException(ErrorCode.SESSION_BUSY, "该会话正在处理中，请稍后再试。", sessionId);
        }
        messages.deleteByQuery(QueryWrapper.create().where("session_id = ?", sessionId));
        reports.deleteByQuery(QueryWrapper.create().where("session_id = ?", sessionId));
        snapshots.deleteByQuery(QueryWrapper.create().where("session_id = ?", sessionId));
        audit.deleteByQuery(QueryWrapper.create().where("session_id = ?", sessionId));
        sessions.deleteById(sessionId);
        SessionTransitionLog.afterCommit(() -> log.info(
                "Session operation completed: sessionId={}, operation=delete, result=DELETED", sessionId));
    }

    private RepairSession current(Accepted accepted) {
        RepairSession session = sessions.lockById(accepted.sessionId());
        if (session == null || !Objects.equals(session.getUserId(), accepted.userId())
                || !Objects.equals(session.getProcessingMessageId(), accepted.messageId())
                || session.getProcessingDeadlineAt() == null
                || !sessions.databaseNow().isBefore(session.getProcessingDeadlineAt())) {
            log.debug("Stale callback ignored: sessionId={}, messageId={}", accepted.sessionId(), accepted.messageId());
            return null;
        }
        return session;
    }

    private boolean expireLocked(RepairSession session, long expectedMessageId) {
        if (!Objects.equals(session.getProcessingMessageId(), expectedMessageId)
                || session.getProcessingDeadlineAt() == null
                || sessions.databaseNow().isBefore(session.getProcessingDeadlineAt())) return false;
        ProblemReport report = reports.latest(session.getId());
        finishLocked(session, expectedMessageId, report == null ? 1 : report.getRound(), FAILED_REQUEST,
                "本次处理已超时，请重新提问。", null, ErrorCode.REQUEST_TIMEOUT);
        SessionTransitionLog.afterCommit(() -> log.warn("Session request timed out: sessionId={}, messageId={}",
                session.getId(), expectedMessageId));
        return true;
    }

    private void failLegacyLocked(RepairSession session) {
        ProblemReport report = reports.latest(session.getId());
        finishLocked(session, 0, report == null ? 1 : report.getRound(), FAILED_REQUEST,
                "原处理上下文已失效，请重新提问。", null, ErrorCode.CONTEXT_EXPIRED);
    }

    private void finishLocked(RepairSession session, long messageId, int round, SessionStatus target,
                              String visible, ConclusionDto conclusion, ErrorCode error) {
        if (visible == null || visible.isBlank()) throw new IllegalArgumentException("Visible result is required");
        saveMessage(session.getId(), "ASSISTANT", visible);
        if (error != null) {
            session.setConclusionType("ERROR");
            session.setConclusion(visible);
            session.setConclusionExtra(encode(Map.of("code", error.name())));
        } else if (conclusion != null) {
            session.setConclusionType(conclusion.type());
            session.setConclusion(conclusion.summary());
            session.setConclusionExtra(encode(conclusion));
        }
        SessionStatus from = SessionStatus.valueOf(session.getStatus());
        if (from != target) transitions.transit(session, from, target);
        session.setProcessingMessageId(null);
        session.setProcessingDeadlineAt(null);
        sessions.update(session, false);
        trace(session.getId(), "request:completed", error == null ? "SUCCESS" : "FAILED", messageId, round,
                error == null ? null : error.name());
        long sessionId = session.getId();
        SessionTransitionLog.afterCommit(() -> log.info(
                "Session request completed: sessionId={}, messageId={}, round={}, result={}, errorCode={}",
                sessionId, messageId == 0 ? null : messageId, round, error == null ? "SUCCESS" : "FAILED", error));
    }

    private RepairSession lockOwned(AuthUser user, long sessionId) {
        requireUser(user);
        RepairSession session = sessions.lockById(sessionId);
        if (session == null) {
            throw new ApiException(ErrorCode.SESSION_NOT_FOUND, "会话不存在", sessionId);
        }
        if (!Objects.equals(session.getUserId(), user.userId())) {
            throw new ApiException(ErrorCode.DEVICE_FORBIDDEN, "无权访问该会话", sessionId);
        }
        return session;
    }

    private void preserveConclusion(RepairSession session) {
        if (session.getConclusion() == null) return;
        // Older workers may have stored only a summary in history. Preserve the complete projection.
        String visible = session.getConclusion();
        if (session.getConclusionExtra() != null) {
            try {
                var extra = json.readTree(session.getConclusionExtra());
                for (String field : new String[]{"manualSteps", "afterSales"}) {
                    if (extra.hasNonNull(field)) visible += "\n" + extra.get(field).toString();
                }
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Stored conclusion cannot be decoded");
            }
        }
        var last = messages.historyBefore(session.getId(), Long.MAX_VALUE);
        String content = visible;
        if (last.stream().noneMatch(m -> "ASSISTANT".equals(m.getRole()) && content.equals(m.getContent()))) {
            saveMessage(session.getId(), "ASSISTANT", visible);
        }
    }

    private ChatMessage saveMessage(long sessionId, String role, String content) {
        ChatMessage message = new ChatMessage();
        message.setSessionId(sessionId);
        message.setRole(role);
        message.setContent(content);
        message.setCreatedAt(sessions.databaseNow());
        messages.insert(message);
        return message;
    }

    private void trace(long sessionId, String action, String result, long messageId, int round, String detail) {
        RepairActionLog entry = new RepairActionLog();
        entry.setSessionId(sessionId);
        entry.setActionCode(action);
        entry.setResult(result);
        entry.setParams(encode(messageId > 0 ? Map.of("messageId", messageId, "round", round) : Map.of("round", round)));
        entry.setMessage(detail);
        audit.insert(entry);
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Session result serialization failed"); }
    }

    private static void requireUser(AuthUser user) {
        if (user == null || user.userId() == null) throw new ApiException(ErrorCode.UNAUTHORIZED, "请先登录");
    }

    private static void requireText(String text, Boolean confirm) {
        if ((text == null || text.isBlank()) && confirm == null) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "请输入消息内容");
        }
    }
}
