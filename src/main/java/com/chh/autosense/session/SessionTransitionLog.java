package com.chh.autosense.session;

import com.chh.autosense.domain.enums.ActionResult;
import com.chh.autosense.domain.enums.SessionStatus;
import com.chh.autosense.domain.model.RepairActionLog;
import com.chh.autosense.domain.model.RepairSession;
import com.chh.autosense.repository.RepairActionLogMapper;
import com.chh.autosense.repository.RepairSessionMapper;
import com.chh.autosense.session.statemachine.SessionStateMachine;
import org.springframework.stereotype.Component;

/**
 * 状态迁移助手(T016):校验合法性 → 更新 repair_session → 写 repair_action_log(FR-013)。
 */
@Component
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
    }
}
