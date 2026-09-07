package com.chh.autosense.core.analysis;

import com.chh.autosense.ai.model.DiagnosisConclusion;
import com.chh.autosense.core.session.memory.ConversationHistorySnapshot;

import java.util.Map;

/**
 * 诊断推理（AI 候选输出）。likelyAutoFixable 不构成任何操作授权。
 * 实现：MockDiagnosisReasoner（显式 mock）/ LangChain4j 真实代理（real）。
 */
public interface DiagnosisReasoner {

    /**
     * @param text        用户问题原文
     * @param symptom     分析阶段的候选症状（可为 null）
     * @param diagnostics 服务端权限核对后取得的只读资料（可为 null）
     * @param history     同轮只读历史快照
     */
    DiagnosisConclusion diagnose(String text, String symptom, Map<String, Object> diagnostics,
                                 ConversationHistorySnapshot history);
}
