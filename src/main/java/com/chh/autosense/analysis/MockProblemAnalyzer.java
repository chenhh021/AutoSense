package com.chh.autosense.analysis;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 开发用内置语义分析桩(autosense.llm.mode=mock):基于关键词的确定性提取,
 * 保证无真实模型时可走通全流程。生产必须切换为 LangChain4j 实现(章程原则 IV)。
 * 本期仅智能灯泡在支持清单(2026-08-27 澄清);路由器/空调会被识别但遭拒识(FR-004)。
 */
@Component
@ConditionalOnProperty(name = "autosense.llm.mode", havingValue = "mock", matchIfMissing = true)
public class MockProblemAnalyzer implements ProblemAnalyzer {

    @Override
    public ProblemAnalysis analyze(String userText, long sessionId) {
        String text = userText == null ? "" : userText;
        String deviceType = detectDeviceType(text);
        if (deviceType == null && !text.isBlank()) {
            return new ProblemAnalysis(null, null, null, false,
                    "请问出问题的设备是什么类型?目前支持:智能灯泡。");
        }
        if (deviceType != null && text.length() < 6) {
            return new ProblemAnalysis(deviceType, null, null, false,
                    "能具体描述一下问题的表现吗?比如什么时候开始出现、如何复现?");
        }
        return new ProblemAnalysis(deviceType, text, null, deviceType != null, null);
    }

    private String detectDeviceType(String text) {
        if (text.contains("灯")) {
            return "smart_bulb";
        }
        if (text.contains("路由") || text.toLowerCase().contains("wifi")
                || text.toLowerCase().contains("wi-fi")) {
            return "router";
        }
        if (text.contains("空调")) {
            return "air_conditioner";
        }
        return null;
    }
}
