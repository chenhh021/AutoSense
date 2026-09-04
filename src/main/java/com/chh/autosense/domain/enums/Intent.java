package com.chh.autosense.domain.enums;

/**
 * 意图路由分类(FR-019/R13)。MODEL_SPECIFIC 本期并入 COMMON_SENSE 处理
 * (型号 RAG 后续作为准确率增强接入);UNCLEAR 进入 CLARIFYING 追问,不触碰设备。
 */
public enum Intent {
    /** 常识性问题→直接回答 */
    COMMON_SENSE,
    /** 可自动修复/操作的问题→诊断修复流程 */
    DEVICE_ACTION,
    /** 型号特异性问题→本期并入 COMMON_SENSE */
    MODEL_SPECIFIC,
    /** 网点查询→售后网点信息 */
    AFTERSALES_QUERY,
    /** 无法确定→追问澄清 */
    UNCLEAR
}
