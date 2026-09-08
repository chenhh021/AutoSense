package com.chh.autosense.core.routing;

import com.chh.autosense.ai.model.RoutingDecision;
import com.chh.autosense.ai.model.enums.CapabilityIntent;
import com.chh.autosense.ai.model.enums.DiagnosisMode;
import com.chh.autosense.ai.model.enums.RoutingOutcome;
import com.chh.autosense.core.session.memory.ConversationHistorySnapshot;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Deterministic offline examples only; real semantic routing uses the resource-based AI Service. */
@Component
@ConditionalOnProperty(name = "autosense.llm.mode", havingValue = "mock", matchIfMissing = true)
public class MockIntentClassifier implements IntentClassifier {
    private static final String CLARIFY = "您好，请问我有什么可以帮您的吗？";

    @Override
    public RoutingDecision classify(String text, ConversationHistorySnapshot history) {
        String t = text == null ? "" : text;
        boolean writes = has(t, "打开", "关闭", "调节", "调整", "调到", "调亮", "调暗", "启动", "停止", "设置");
        boolean question = has(t, "吗", "？", "?", "是否", "能否", "支持", "怎么", "如何", "适合", "够不够", "估算", "计算");
        boolean deviceReference = referencesDevice(t) || (has(t, "它", "这台", "这个", "该设备")
                && history != null && history.messages().stream()
                .anyMatch(m -> "USER".equals(m.role()) && referencesDevice(m.content())));
        boolean productSpecific = t.matches("(?s).*[A-Za-z]{1,5}[0-9]{2,}.*")
                || has(t, "型号", "规格", "说明书", "手册", "产品文档", "厂商");
        boolean parameters = has(t, "参数", "功率", "耗电", "亮度", "色温", "调色", "电量", "温度", "功能", "状态", "规格");
        boolean parameterQuestion = deviceReference && parameters && (question || has(t, "多少", "根据", "结合", "基于"));
        boolean explicitWrite = writes && (!parameterQuestion || has(t, "请打开", "请关闭", "请设置", "调到", "调亮", "调暗", "帮我", "把", "将"));
        if (t.contains("如果") || t.contains("若 ") || t.startsWith("若")
                || (t.contains("并") && explicitWrite)
                || (has(t, "所有", "全部") && explicitWrite)) {
            return new RoutingDecision(RoutingOutcome.COMPOSITE, null, null, null, CLARIFY, null);
        }
        if (has(t, "售后", "网点", "维修点", "服务点", "保修")) {
            return new RoutingDecision(RoutingOutcome.SINGLE, CapabilityIntent.DIAGNOSIS,
                    DiagnosisMode.AFTERSALES, null, null, null);
        }
        if (explicitWrite) return single(CapabilityIntent.CONTROL, null);
        if (has(t, "不亮", "太暗", "不工作", "坏了", "故障", "离线", "不响应")) {
            return new RoutingDecision(RoutingOutcome.SINGLE, CapabilityIntent.DIAGNOSIS,
                    DiagnosisMode.DEFAULT, null, null, null);
        }
        if (parameterQuestion || (deviceReference && has(t, "参数", "多少", "状态", "支持", "功能", "型号"))
                || (has(t, "列出", "有哪些") && has(t, "设备", "灯"))
                || (!productSpecific && has(t, "查询", "多少", "状态") && !has(t, "什么是", "含义", "原理"))) {
            return single(CapabilityIntent.DEVICE_QUERY, null);
        }
        if (has(t, "诗", "笑话", "天气", "新闻", "股票", "翻译")) {
            return new RoutingDecision(RoutingOutcome.OUT_OF_SCOPE, null, null, null, null, null);
        }
        if (productSpecific || has(t, "支持", "参数", "功能", "什么是", "含义", "原理", "寿命", "多久", "怎么", "如何")) {
            boolean commonSense = !productSpecific && !has(t, "支持", "参数", "功能", "设置", "使用方法")
                    && has(t, "什么是", "含义", "原理", "寿命", "多久");
            return single(CapabilityIntent.KNOWLEDGE, !commonSense);
        }
        return new RoutingDecision(RoutingOutcome.CLARIFY, null, null, null, CLARIFY, null);
    }

    private static RoutingDecision single(CapabilityIntent intent, Boolean requiresKnowledgeBase) {
        return new RoutingDecision(RoutingOutcome.SINGLE, intent, null, null, null, requiresKnowledgeBase);
    }

    private static boolean referencesDevice(String text) {
        return text != null && has(text, "我的", "我家", "客厅", "卧室", "厨房", "书房", "绑定");
    }

    private static boolean has(String text, String... words) {
        for (String word : words) if (text.contains(word)) return true;
        return false;
    }
}
