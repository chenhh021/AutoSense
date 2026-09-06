package com.chh.autosense.core.analysis;

import java.util.Map;

/**
 * 诊断推理(FR-006)。实现:MockDiagnosisReasoner(dev)/ LangChain4j 实现(prod)。
 *
 * @param sessionId 用于对话记忆(memoryId,R15)
 */
public interface DiagnosisReasoner {

    DiagnosisConclusion diagnose(String userText, String symptom, Map<String, Object> diagnostics,
                                 long sessionId);
}
