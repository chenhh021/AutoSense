package com.chh.autosense.unit;

import com.chh.autosense.core.analysis.MockProblemAnalyzer;
import com.chh.autosense.core.analysis.ProblemAnalysis;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MockProblemAnalyzerTest {

    private final MockProblemAnalyzer analyzer = new MockProblemAnalyzer();

    @Test
    void 识别设备类型与充分性() {
        ProblemAnalysis bulb = analyzer.analyze("客厅的灯不亮了,昨晚开始就这样", 1L);
        assertThat(bulb.deviceType()).isEqualTo("smart_bulb");
        assertThat(bulb.sufficient()).isTrue();

        // 路由器/空调仍被识别(由编排层按支持清单拒识,FR-004)
        assertThat(analyzer.analyze("卧室空调开了半小时不制冷", 1L).deviceType())
                .isEqualTo("air_conditioner");
        assertThat(analyzer.analyze("Wi-Fi 经常断连,重启也没用", 1L).deviceType())
                .isEqualTo("router");
    }

    @Test
    void 无法识别设备类型时给出澄清问题() {
        ProblemAnalysis result = analyzer.analyze("扫地机器人不工作了", 1L);
        assertThat(result.sufficient()).isFalse();
        assertThat(result.clarifyQuestion()).contains("智能灯泡");
    }

    @Test
    void 描述过短时追问问题表现() {
        ProblemAnalysis result = analyzer.analyze("灯坏了", 1L);
        assertThat(result.deviceType()).isEqualTo("smart_bulb");
        assertThat(result.sufficient()).isFalse();
        assertThat(result.clarifyQuestion()).isNotBlank();
    }
}
