package com.chh.autosense.core.analysis;

import com.chh.autosense.ai.model.DiagnosisConclusion;
import com.chh.autosense.core.session.memory.ConversationHistorySnapshot;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 开发用内置诊断推理桩（autosense.llm.mode=mock）：故障结论由规则引擎（R12）产生，
 * 本桩仅负责把 state 与症状组织为可理解文本（FR-006）。
 * 生产必须切换为真实 AI Service 代理（章程原则 IV）。
 */
@Component
@ConditionalOnProperty(name = "autosense.llm.mode", havingValue = "mock", matchIfMissing = true)
public class MockDiagnosisReasoner implements DiagnosisReasoner {

    @Override
    public DiagnosisConclusion diagnose(String text, String symptom,
                                        Map<String, Object> diagnostics,
                                        ConversationHistorySnapshot history) {
        String stateText = diagnostics == null ? "{}" : diagnostics.toString();
        return new DiagnosisConclusion(
                symptom == null ? "设备状态检查" : symptom,
                "已读取设备当前状态：%s。".formatted(stateText),
                true);
    }
}
