package com.chh.autosense.domain.enums;

/**
 * 会话结论类型(data-model.md §3 conclusion_type)。
 */
public enum ConclusionType {
    /** 已自动修复并复检通过 */
    FIXED,
    /** 无法自动修复,给出人工分步指引 */
    UNFIXED_MANUAL_GUIDE,
    /** 无法自动修复且无明确步骤,提供售后网点/官方客服 */
    UNFIXED_AFTERSALES,
    /** 设备不可达,流程终止 */
    DEVICE_UNREACHABLE,
    /** 常识/型号特异性问题已直接回答(FR-019 路由 1/3) */
    ANSWERED,
    /** 独立网点查询已提供结果(FR-019 路由 4) */
    AFTERSALES_PROVIDED
}
