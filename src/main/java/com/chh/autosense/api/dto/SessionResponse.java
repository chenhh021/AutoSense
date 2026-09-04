package com.chh.autosense.api.dto;

/**
 * 会话响应(contracts §1~3):awaitingInput=true 表示等待用户继续;
 * 终态时 conclusion 非空。
 */
public record SessionResponse(
        Long sessionId,
        String status,
        String reply,
        boolean awaitingInput,
        ConclusionDto conclusion
) {
}
