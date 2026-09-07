package com.chh.autosense.domain.enums;

/**
 * 会话状态机(data-model.md §3,2026-08-22/27 扩展:意图路由 + 终态回路 + 全操作确认门)。
 * 等待用户态:CLARIFYING / DEVICE_CONFIRMING / CONFIRMING_REPAIR / AWAITING_LOCATION;
 * 其余由状态机自动推进;任意终态可经用户新消息回到 ROUTING(R17)。
 */
public enum SessionStatus {
    CREATED,
    /** 意图分类(FR-019) */
    ROUTING,
    DISPATCHING,
    FAILED_REQUEST,
    /** 常识/型号问题直答(FR-019 路由 1/3) */
    ANSWERING,
    /** 网点查询执行(FR-019 路由 4 / FR-011) */
    AFTERSALES_LOOKUP,
    ANALYZING,
    CLARIFYING,
    REJECTED_UNSUPPORTED,
    LOCATING,
    DEVICE_CONFIRMING,
    REJECTED_FORBIDDEN,
    REJECTED_BUSY,
    FAILED_DEVICE_UNREACHABLE,
    DIAGNOSING,
    PLANNING,
    CONFIRMING_REPAIR,
    REPAIRING,
    VERIFYING,
    COMPLETED_FIXED,
    COMPLETED_UNFIXED,
    GUIDED_MANUAL,
    GUIDED_AFTERSALES,
    /** 常识/型号问题已回答(终) */
    COMPLETED_ANSWERED,
    /** 网点信息已提供(终) */
    COMPLETED_AFTERSALES,
    /** 等待用户提供位置 */
    AWAITING_LOCATION;

    public boolean isTerminal() {
        return switch (this) {
            case REJECTED_UNSUPPORTED, REJECTED_FORBIDDEN, REJECTED_BUSY,
                 FAILED_DEVICE_UNREACHABLE, COMPLETED_FIXED, COMPLETED_UNFIXED,
                 GUIDED_MANUAL, COMPLETED_ANSWERED, COMPLETED_AFTERSALES, FAILED_REQUEST -> true;
            default -> false;
        };
    }

    public boolean isAwaitingUser() {
        return switch (this) {
            case CLARIFYING, DEVICE_CONFIRMING, CONFIRMING_REPAIR, AWAITING_LOCATION -> true;
            default -> false;
        };
    }
}
