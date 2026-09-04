package com.chh.autosense.routing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * mock 模式直答(LLM_MODE=mock):确定性 canned 回答,按固定小块逐段回放,
 * 支撑 SSE token 事件的确定性测试(FR-021)。
 */
@Component
@ConditionalOnProperty(name = "autosense.llm.mode", havingValue = "mock", matchIfMissing = true)
public class MockDirectAnswerer implements DirectAnswerer {

    @Override
    public CompletionStage<String> answer(String question, long sessionId,
                                          Consumer<String> onToken) {
        String full = cannedAnswer(question);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < full.length(); i += 8) {
            String chunk = full.substring(i, Math.min(i + 8, full.length()));
            sb.append(chunk);
            onToken.accept(chunk);
        }
        return CompletableFuture.completedFuture(sb.toString());
    }

    private String cannedAnswer(String question) {
        String q = question == null ? "" : question;
        if (q.contains("寿命") || q.contains("多久")) {
            return "智能灯泡的一般寿命约为 15000~25000 小时,按每天使用 8 小时计算约可使用 5~8 年。"
                    + "实际寿命受使用环境、开关频率与散热条件影响。";
        }
        if (q.contains("LA001")) {
            return "LA001 为单色灯泡型号,支持亮度与色温调节,不支持彩色;如需彩光请选择 LB001 型号。";
        }
        if (q.contains("LB001")) {
            return "LB001 为彩光灯泡型号,支持亮度与 1600 万色 RGB 颜色调节。";
        }
        return "感谢您的提问。这是一类常识性问题,一般建议参考设备说明书或品牌官网获取权威信息;"
                + "若设备存在实际故障,请描述具体现象,我可以为您诊断。";
    }
}
