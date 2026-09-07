package com.chh.autosense.core.routing;

import com.chh.autosense.ai.model.RoutingDecision;
import com.chh.autosense.core.session.memory.ConversationHistorySnapshot;

/**
 * 意图分类：输出 AI 候选 RoutingDecision（routing-contract §2）。
 * 分类结果只是候选数据，不构成身份、设备归属或任何授权。
 * 实现：MockIntentClassifier（显式 mock 模式）/ LangChain4j 真实代理适配（real 模式）。
 */
public interface IntentClassifier {

    /**
     * @param text    本次被接纳的用户输入原文
     * @param history 同轮只读历史快照（当前 messageId 之前最近 20 条 USER/ASSISTANT）
     */
    RoutingDecision classify(String text, ConversationHistorySnapshot history);
}
