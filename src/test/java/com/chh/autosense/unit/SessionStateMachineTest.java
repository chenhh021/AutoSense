package com.chh.autosense.unit;

import com.chh.autosense.domain.enums.SessionStatus;
import com.chh.autosense.core.session.statemachine.SessionStateMachine;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionStateMachineTest {

    private final SessionStateMachine machine = new SessionStateMachine();

    @Test
    void 合法迁移全部按状态机定义放行() {
        assertThat(machine.canTransit(SessionStatus.CREATED, SessionStatus.ROUTING)).isTrue();
        assertThat(machine.canTransit(SessionStatus.ROUTING, SessionStatus.ANALYZING)).isTrue();
        assertThat(machine.canTransit(SessionStatus.ROUTING, SessionStatus.ANSWERING)).isTrue();
        assertThat(machine.canTransit(SessionStatus.ROUTING, SessionStatus.AFTERSALES_LOOKUP)).isTrue();
        assertThat(machine.canTransit(SessionStatus.ROUTING, SessionStatus.CLARIFYING)).isTrue();
        assertThat(machine.canTransit(SessionStatus.ANALYZING, SessionStatus.CLARIFYING)).isTrue();
        assertThat(machine.canTransit(SessionStatus.ANALYZING, SessionStatus.REJECTED_UNSUPPORTED)).isTrue();
        assertThat(machine.canTransit(SessionStatus.LOCATING, SessionStatus.DEVICE_CONFIRMING)).isTrue();
        assertThat(machine.canTransit(SessionStatus.DIAGNOSING, SessionStatus.PLANNING)).isTrue();
        assertThat(machine.canTransit(SessionStatus.PLANNING, SessionStatus.CONFIRMING_REPAIR)).isTrue();
        assertThat(machine.canTransit(SessionStatus.CONFIRMING_REPAIR, SessionStatus.REPAIRING)).isTrue();
        assertThat(machine.canTransit(SessionStatus.REPAIRING, SessionStatus.VERIFYING)).isTrue();
        assertThat(machine.canTransit(SessionStatus.VERIFYING, SessionStatus.COMPLETED_FIXED)).isTrue();
        assertThat(machine.canTransit(SessionStatus.VERIFYING, SessionStatus.GUIDED_MANUAL)).isTrue();
        assertThat(machine.canTransit(SessionStatus.AWAITING_LOCATION, SessionStatus.AFTERSALES_LOOKUP)).isTrue();
        assertThat(machine.canTransit(SessionStatus.GUIDED_AFTERSALES, SessionStatus.AFTERSALES_LOOKUP)).isTrue();
    }

    @Test
    void 非法迁移被拒绝() {
        assertThat(machine.canTransit(SessionStatus.CREATED, SessionStatus.REPAIRING)).isFalse();
        assertThat(machine.canTransit(SessionStatus.CREATED, SessionStatus.ANALYZING)).isFalse();
        assertThat(machine.canTransit(SessionStatus.PLANNING, SessionStatus.COMPLETED_FIXED)).isFalse();
        assertThat(machine.canTransit(SessionStatus.CLARIFYING, SessionStatus.VERIFYING)).isFalse();
    }

    @Test
    void 终态仅可回到ROUTING开启新一轮_R17() {
        assertThat(SessionStatus.COMPLETED_FIXED.isTerminal()).isTrue();
        assertThat(SessionStatus.COMPLETED_ANSWERED.isTerminal()).isTrue();
        assertThat(machine.canTransit(SessionStatus.COMPLETED_FIXED, SessionStatus.ROUTING)).isTrue();
        assertThat(machine.canTransit(SessionStatus.COMPLETED_ANSWERED, SessionStatus.ROUTING)).isTrue();
        assertThat(machine.canTransit(SessionStatus.COMPLETED_FIXED, SessionStatus.ANALYZING)).isFalse();
        assertThatThrownBy(() -> machine.assertTransit(
                SessionStatus.COMPLETED_FIXED, SessionStatus.ANALYZING))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void GUIDED_AFTERSALES不再是终态_可继续售后查询() {
        assertThat(SessionStatus.GUIDED_AFTERSALES.isTerminal()).isFalse();
        assertThat(machine.canTransit(SessionStatus.GUIDED_AFTERSALES, SessionStatus.ROUTING)).isFalse();
    }

    @Test
    void 等待用户态识别() {
        assertThat(SessionStatus.CLARIFYING.isAwaitingUser()).isTrue();
        assertThat(SessionStatus.CONFIRMING_REPAIR.isAwaitingUser()).isTrue();
        assertThat(SessionStatus.AWAITING_LOCATION.isAwaitingUser()).isTrue();
        assertThat(SessionStatus.REPAIRING.isAwaitingUser()).isFalse();
    }
}
