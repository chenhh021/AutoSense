package com.chh.autosense.core.routing;

import com.chh.autosense.domain.enums.Intent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * mock 模式意图分类(LLM_MODE=mock):关键词路由,确定性可测。
 * MODEL_SPECIFIC 本期并入 COMMON_SENSE(2026-08-22 澄清)。
 */
@Component
@ConditionalOnProperty(name = "autosense.llm.mode", havingValue = "mock", matchIfMissing = true)
public class MockIntentClassifier implements IntentClassifier {

    @Override
    public Intent classify(String text, long sessionId) {
        String t = text == null ? "" : text;
        if (t.contains("售后") || t.contains("网点") || t.contains("维修点") || t.contains("服务点")) {
            return Intent.AFTERSALES_QUERY;
        }
        if (t.contains("不亮") || t.contains("太暗") || t.contains("不工作") || t.contains("坏了")
                || t.contains("故障") || t.contains("离线") || t.contains("不响应")
                || t.contains("调") || t.contains("启动") || t.contains("停止")) {
            return Intent.DEVICE_ACTION;
        }
        if (t.contains("型号") || t.contains("支持") || t.contains("参数") || t.contains("规格")) {
            return Intent.MODEL_SPECIFIC;
        }
        if (t.endsWith("?") || t.endsWith("?") || t.contains("多久") || t.contains("怎么")
                || t.contains("什么是") || t.contains("为什么") || t.contains("如何")) {
            return Intent.COMMON_SENSE;
        }
        return Intent.UNCLEAR;
    }
}
