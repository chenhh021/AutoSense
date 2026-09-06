package com.chh.autosense.core.session.statemachine;

import com.chh.autosense.domain.enums.SessionStatus;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static com.chh.autosense.domain.enums.SessionStatus.*;

/**
 * 会话状态机(data-model.md §3,2026-08-22/27 扩展):迁移合法性校验。
 * 扩展点:入口 ROUTING 意图分类(FR-019);CONFIRMING_REPAIR 覆盖全部改变状态的操作
 * (FR-008);任意终态可回到 ROUTING 开启新一轮(R17 终态续聊);
 * PLANNING 支持规则"无异常"→COMPLETED_ANSWERED(R12)。
 * 所有迁移须写 repair_action_log(由编排层落日志,FR-013)。
 */
@Component
public class SessionStateMachine {

    private static final Map<SessionStatus, Set<SessionStatus>> TRANSITIONS = buildTransitions();

    private static Map<SessionStatus, Set<SessionStatus>> buildTransitions() {
        Map<SessionStatus, Set<SessionStatus>> m = new HashMap<>();
        m.put(CREATED, EnumSet.of(ROUTING));
        m.put(ROUTING, EnumSet.of(ANSWERING, AFTERSALES_LOOKUP, AWAITING_LOCATION,
                ANALYZING, CLARIFYING));
        m.put(ANSWERING, EnumSet.of(COMPLETED_ANSWERED));
        m.put(AFTERSALES_LOOKUP, EnumSet.of(COMPLETED_AFTERSALES));
        m.put(ANALYZING, EnumSet.of(CLARIFYING, REJECTED_UNSUPPORTED, LOCATING));
        m.put(CLARIFYING, EnumSet.of(ROUTING, ANALYZING));
        m.put(LOCATING, EnumSet.of(DEVICE_CONFIRMING, REJECTED_FORBIDDEN,
                REJECTED_BUSY, FAILED_DEVICE_UNREACHABLE, DIAGNOSING));
        m.put(DEVICE_CONFIRMING, EnumSet.of(LOCATING));
        m.put(DIAGNOSING, EnumSet.of(PLANNING, FAILED_DEVICE_UNREACHABLE));
        m.put(PLANNING, EnumSet.of(CONFIRMING_REPAIR, GUIDED_MANUAL,
                GUIDED_AFTERSALES, COMPLETED_ANSWERED));
        m.put(GUIDED_AFTERSALES, EnumSet.of(AFTERSALES_LOOKUP, AWAITING_LOCATION));
        m.put(AWAITING_LOCATION, EnumSet.of(AFTERSALES_LOOKUP));
        m.put(CONFIRMING_REPAIR, EnumSet.of(REPAIRING, COMPLETED_UNFIXED));
        m.put(REPAIRING, EnumSet.of(VERIFYING));
        m.put(VERIFYING, EnumSet.of(COMPLETED_FIXED, GUIDED_MANUAL, GUIDED_AFTERSALES));
        // R17:任意终态可经用户新消息回到 ROUTING 开启新一轮
        for (SessionStatus s : values()) {
            if (s.isTerminal()) {
                m.put(s, EnumSet.of(ROUTING));
            }
        }
        return Map.copyOf(m);
    }

    public boolean canTransit(SessionStatus from, SessionStatus to) {
        if (from == null) {
            return false;
        }
        return TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    public void assertTransit(SessionStatus from, SessionStatus to) {
        if (!canTransit(from, to)) {
            throw new IllegalStateException(
                    "非法状态迁移: %s -> %s".formatted(from, to));
        }
    }
}
