package com.chh.autosense.core.session;

import com.chh.autosense.domain.enums.ActionResult;
import com.chh.autosense.domain.enums.SessionStatus;
import com.chh.autosense.domain.entity.RepairActionLog;
import com.chh.autosense.domain.entity.RepairSession;
import com.chh.autosense.mapper.RepairActionLogMapper;
import com.chh.autosense.mapper.RepairSessionMapper;
import com.chh.autosense.core.session.statemachine.SessionStateMachine;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;
import com.chh.autosense.utils.LogContextUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 状态迁移助手(T016):校验合法性 → 更新 repair_session → 写 repair_action_log(FR-013)。
 */
@Component
@Slf4j
public class SessionTransitionLog {

    private final SessionStateMachine stateMachine;
    private final RepairSessionMapper sessionMapper;
    private final RepairActionLogMapper actionLogMapper;

    public SessionTransitionLog(SessionStateMachine stateMachine,
                                RepairSessionMapper sessionMapper,
                                RepairActionLogMapper actionLogMapper) {
        this.stateMachine = stateMachine;
        this.sessionMapper = sessionMapper;
        this.actionLogMapper = actionLogMapper;
    }

    public void transit(RepairSession session, SessionStatus from, SessionStatus to) {
        stateMachine.assertTransit(from, to);
        session.setStatus(to.name());
        sessionMapper.update(session);
        RepairActionLog logEntry = new RepairActionLog();
        logEntry.setSessionId(session.getId());
        logEntry.setActionCode("state:%s->%s".formatted(from, to));
        logEntry.setResult(ActionResult.SUCCESS.name());
        logEntry.setMessage(null);
        actionLogMapper.insert(logEntry);
        long sessionId = session.getId();
        afterCommit(() -> log.info("Session state changed: sessionId={}, fromState={}, toState={}",
                sessionId, from, to));
    }

    public static void afterCommit(Runnable action) {
        Runnable contextual = LogContextUtils.wrap(LogContextUtils.snapshot(), action);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { contextual.run(); }
            });
        } else {
            contextual.run();
        }
    }
}
