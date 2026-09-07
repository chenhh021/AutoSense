package com.chh.autosense.core.routing;

import com.chh.autosense.core.session.memory.ConversationHistorySnapshot;

import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * 直接回答（知识类能力的流式生成，供 003 等业务处理器复用）：不触碰任何设备。
 * 模型/流式失败以异常完成，不生成兜底成功回答。
 */
public interface DirectAnswerer {

    /**
     * @param question 用户问题原文（映射为 text）
     * @param history  同轮只读历史快照
     * @param onToken  逐 token 回调
     * @return 流式完成后携带完整回答文本的异步结果；失败时异常完成
     */
    CompletionStage<String> answer(String question, ConversationHistorySnapshot history,
                                   Consumer<String> onToken);
}
