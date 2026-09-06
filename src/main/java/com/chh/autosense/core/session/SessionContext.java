package com.chh.autosense.core.session;

import com.chh.autosense.domain.enums.SessionStatus;

/**
 * 进行中会话上下文(Redis,键 autosense:session:{id},30min 滚动 TTL,R6)。
 * 终态会话以 MySQL 为准。
 */
public record SessionContext(
        Long sessionId,
        Long userId,
        SessionStatus status,
        /** 多候选设备时的候选 ID 列表(JSON 数组) */
        String candidateDeviceIds,
        /** 已确认的目标设备 */
        Long deviceId,
        /** 已提取的设备类型 */
        String deviceType,
        /** 等待中的修复动作码(CONFIRMING_REPAIR) */
        String pendingActionCode,
        /** 等待中的修复动作参数 JSON(CONFIRMING_REPAIR,来自规则引擎) */
        String pendingActionParams
) {
}
