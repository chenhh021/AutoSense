package com.chh.autosense.analysis;

/**
 * 语义分析(FR-002)。实现:MockProblemAnalyzer(dev)/ LangChain4j 实现(prod)。
 *
 * @param sessionId 用于对话记忆(memoryId,最近 20 条历史可见,R15)
 */
public interface ProblemAnalyzer {

    ProblemAnalysis analyze(String userText, long sessionId);
}
