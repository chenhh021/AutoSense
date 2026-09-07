package com.chh.autosense.core.session;

/**
 * 能力等待上下文快照（Redis 键 autosense:session:v2:{sessionId}，data-model §6）。
 * 完整 JSON 原子替换；version 用于使旧格式/旧字段安全失效。
 * 只记录归属与轮次等关联标识，不保存任何控制授权或确认结论。
 */
public record SessionContext(
        int version,
        Long sessionId,
        Long userId,
        Integer round,
        /** 进入等待态时本轮的处理消息 ID */
        Long messageId,
        /** 拥有该等待态的能力名（AssistantCapability） */
        String capability,
        /** 等待态状态名（SessionStatus） */
        String waitingState
) {
    public static final int CURRENT_VERSION = 2;
}
