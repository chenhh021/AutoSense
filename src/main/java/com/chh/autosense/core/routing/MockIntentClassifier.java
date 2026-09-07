package com.chh.autosense.core.routing;

import com.chh.autosense.ai.model.RoutingDecision;
import com.chh.autosense.ai.model.enums.CapabilityIntent;
import com.chh.autosense.ai.model.enums.DiagnosisMode;
import com.chh.autosense.ai.model.enums.RoutingOutcome;
import com.chh.autosense.core.session.memory.ConversationHistorySnapshot;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Deterministic keyword routing for the explicit mock mode (autosense.llm.mode=mock).
 * Keeps the four capability intents, aftersales sub-mode and ambiguity/composite/out-of-scope
 * outcomes separate for offline tests. Never registers or implies test capability handlers.
 */
@Component
@ConditionalOnProperty(name = "autosense.llm.mode", havingValue = "mock", matchIfMissing = true)
public class MockIntentClassifier implements IntentClassifier {

    private static final String CLARIFY =
            "请说明您希望咨询知识、查询设备、诊断故障，还是控制设备；本轮先处理一项。";

    @Override
    public RoutingDecision classify(String text, ConversationHistorySnapshot history) {
        String t = text == null ? "" : text;
        boolean writes = t.contains("打开") || t.contains("关闭") || t.contains("调")
                || t.contains("启动") || t.contains("停止") || t.contains("设置");
        // Composite: conditional, multi-intent, or multi-device writes.
        if (t.contains("如果") || t.contains("若 ") || t.startsWith("若")) {
            return new RoutingDecision(RoutingOutcome.COMPOSITE, null, null, null, CLARIFY);
        }
        if (t.contains("并") && writes) {
            return new RoutingDecision(RoutingOutcome.COMPOSITE, null, null, null, CLARIFY);
        }
        if ((t.contains("所有") || t.contains("全部")) && writes) {
            return new RoutingDecision(RoutingOutcome.COMPOSITE, null, null, null, CLARIFY);
        }
        if (t.contains("售后") || t.contains("网点") || t.contains("维修点") || t.contains("服务点")) {
            return new RoutingDecision(RoutingOutcome.SINGLE, CapabilityIntent.DIAGNOSIS,
                    DiagnosisMode.AFTERSALES, null, null);
        }
        if (writes) {
            return new RoutingDecision(RoutingOutcome.SINGLE, CapabilityIntent.CONTROL, null, null, null);
        }
        if (t.contains("列出") || t.contains("查询") || t.contains("多少") || t.contains("状态")) {
            return new RoutingDecision(RoutingOutcome.SINGLE, CapabilityIntent.DEVICE_QUERY, null, null, null);
        }
        if (t.contains("不亮") || t.contains("太暗") || t.contains("不工作") || t.contains("坏了")
                || t.contains("故障") || t.contains("离线") || t.contains("不响应")) {
            return new RoutingDecision(RoutingOutcome.SINGLE, CapabilityIntent.DIAGNOSIS,
                    DiagnosisMode.DEFAULT, null, null);
        }
        if (t.contains("型号") || t.contains("支持") || t.contains("参数") || t.contains("规格")
                || t.contains("什么是") || t.contains("寿命") || t.contains("多久") || t.contains("怎么")) {
            return new RoutingDecision(RoutingOutcome.SINGLE, CapabilityIntent.KNOWLEDGE, null, null, null);
        }
        if (t.contains("诗") || t.contains("笑话") || t.contains("天气") || t.contains("新闻")
                || t.contains("股票") || t.contains("翻译")) {
            return new RoutingDecision(RoutingOutcome.OUT_OF_SCOPE, null, null, null, null);
        }
        return new RoutingDecision(RoutingOutcome.CLARIFY, null, null, null, CLARIFY);
    }
}
