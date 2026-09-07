package com.chh.autosense.core.analysis;

import com.chh.autosense.ai.model.ProblemAnalysis;
import com.chh.autosense.core.session.memory.ConversationHistorySnapshot;

/**
 * 语义分析（AI 候选输出）。实现：MockProblemAnalyzer（显式 mock）/ LangChain4j 真实代理（real）。
 */
public interface ProblemAnalyzer {

    /**
     * @param text    用户输入原文
     * @param history 同轮只读历史快照（最近 20 条 USER/ASSISTANT）
     */
    ProblemAnalysis analyze(String text, ConversationHistorySnapshot history);
}
