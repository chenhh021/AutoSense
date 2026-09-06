package com.chh.autosense.core.device.rule;

import com.chh.autosense.config.DeviceTypeRegistryProperties.FaultRuleSpec;

import java.util.List;
import java.util.Map;

/**
 * 状态→故障判定结果(R12)。规则按序匹配首个命中;无命中 = NORMAL。
 */
public record FaultVerdict(
        Kind kind,
        /** 命中规则名(NORMAL 时为 null) */
        String ruleName,
        /** 面向用户的原因说明 */
        String reason,
        /** AUTO_REPAIRABLE 时的白名单动作码(模拟器命令名) */
        String actionCode,
        /** AUTO_REPAIRABLE 时的动作参数 */
        Map<String, Object> actionParams,
        /** MANUAL_ONLY 时的知识库引用 */
        String knowledgeRef
) {

    public enum Kind {
        AUTO_REPAIRABLE, MANUAL_ONLY, AFTERSALES, NORMAL
    }

    public static FaultVerdict normal() {
        return new FaultVerdict(Kind.NORMAL, null, null, null, null, null);
    }

    public static FaultVerdict of(FaultRuleSpec rule) {
        Kind kind = Kind.valueOf(rule.conclusion());
        return new FaultVerdict(kind, rule.name(), rule.reason(),
                rule.action(), rule.params() == null ? Map.of() : rule.params(),
                rule.knowledgeRef());
    }

    /** 便捷:规则列表按序评估(state 含 running_status 等键)。 */
    public static FaultVerdict evaluate(List<FaultRuleSpec> rules, Map<String, Object> state,
                                        RuleConditionEvaluator evaluator) {
        if (rules == null) {
            return normal();
        }
        for (FaultRuleSpec rule : rules) {
            if (evaluator.matches(rule.when(), state)) {
                return of(rule);
            }
        }
        return normal();
    }
}
