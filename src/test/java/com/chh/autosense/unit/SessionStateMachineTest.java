package com.chh.autosense.unit;

import com.chh.autosense.domain.enums.SessionStatus;
import com.chh.autosense.core.session.statemachine.SessionStateMachine;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionStateMachineTest {

    private final SessionStateMachine machine = new SessionStateMachine();

    @Test
    void publicDispatchAndFailureTransitionsDoNotGrantDeviceExecution() {
        assertThat(machine.canTransit(SessionStatus.ROUTING, SessionStatus.DISPATCHING)).isTrue();
        assertThat(machine.canTransit(SessionStatus.DISPATCHING, SessionStatus.REPAIRING)).isFalse();
        assertThat(machine.canTransit(SessionStatus.DISPATCHING, SessionStatus.FAILED_REQUEST)).isTrue();
        assertThat(machine.canTransit(SessionStatus.CONFIRMING_REPAIR, SessionStatus.FAILED_REQUEST)).isTrue();
        assertThat(machine.canTransit(SessionStatus.FAILED_REQUEST, SessionStatus.ROUTING)).isTrue();
        assertThat(SessionStatus.GUIDED_MANUAL.isTerminal()).isTrue();
        assertThat(machine.canTransit(SessionStatus.GUIDED_MANUAL, SessionStatus.ROUTING)).isTrue();
    }

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

    @Test
    void 等待态可因取消进入COMPLETED_UNFIXED且可再开新轮() {
        assertThat(machine.canTransit(SessionStatus.CLARIFYING, SessionStatus.COMPLETED_UNFIXED)).isTrue();
        assertThat(machine.canTransit(SessionStatus.DEVICE_CONFIRMING, SessionStatus.COMPLETED_UNFIXED)).isTrue();
        assertThat(machine.canTransit(SessionStatus.AWAITING_LOCATION, SessionStatus.COMPLETED_UNFIXED)).isTrue();
        assertThat(machine.canTransit(SessionStatus.CONFIRMING_REPAIR, SessionStatus.COMPLETED_UNFIXED)).isTrue();
        assertThat(SessionStatus.COMPLETED_UNFIXED.isTerminal()).isTrue();
        assertThat(machine.canTransit(SessionStatus.COMPLETED_UNFIXED, SessionStatus.ROUTING)).isTrue();
    }

    @Test
    void DISPATCHING增量迁移覆盖能力目标与确定性收尾() {
        assertThat(machine.canTransit(SessionStatus.DISPATCHING, SessionStatus.ANSWERING)).isTrue();
        assertThat(machine.canTransit(SessionStatus.DISPATCHING, SessionStatus.ANALYZING)).isTrue();
        assertThat(machine.canTransit(SessionStatus.DISPATCHING, SessionStatus.CLARIFYING)).isTrue();
        assertThat(machine.canTransit(SessionStatus.DISPATCHING, SessionStatus.COMPLETED_ANSWERED)).isTrue();
        assertThat(machine.canTransit(SessionStatus.DISPATCHING, SessionStatus.COMPLETED_UNFIXED)).isTrue();
        // DISPATCHING 不得直达设备执行或确认门之外的修复动作
        assertThat(machine.canTransit(SessionStatus.DISPATCHING, SessionStatus.VERIFYING)).isFalse();
    }

    @Test
    void FAILED_REQUEST与ERROR增量语义() {
        // FAILED_REQUEST 为可续聊的终态失败(R17 可回 ROUTING),不得直达执行
        assertThat(SessionStatus.FAILED_REQUEST.isTerminal()).isTrue();
        assertThat(machine.canTransit(SessionStatus.FAILED_REQUEST, SessionStatus.ROUTING)).isTrue();
        assertThat(machine.canTransit(SessionStatus.FAILED_REQUEST, SessionStatus.REPAIRING)).isFalse();
        assertThat(machine.canTransit(SessionStatus.ROUTING, SessionStatus.FAILED_REQUEST)).isTrue();
        assertThat(machine.canTransit(SessionStatus.ANSWERING, SessionStatus.FAILED_REQUEST)).isTrue();
        assertThat(machine.canTransit(SessionStatus.DISPATCHING, SessionStatus.FAILED_REQUEST)).isTrue();
    }

    @Test
    void GUIDED_MANUAL终态可经新消息开新轮() {
        assertThat(SessionStatus.GUIDED_MANUAL.isTerminal()).isTrue();
        assertThat(machine.canTransit(SessionStatus.GUIDED_MANUAL, SessionStatus.ROUTING)).isTrue();
        assertThat(machine.canTransit(SessionStatus.GUIDED_MANUAL, SessionStatus.REPAIRING)).isFalse();
    }
}
