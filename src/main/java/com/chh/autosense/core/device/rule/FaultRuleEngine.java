package com.chh.autosense.core.device.rule;

import com.chh.autosense.config.DeviceTypeRegistryProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 故障判定规则引擎(R12):读取设备类型注册表 fault-rules,对设备 state 按序匹配。
 * state 中补充 running_status 键(探测时由适配器注入)。
 */
@Component
public class FaultRuleEngine {

    private final DeviceTypeRegistryProperties registry;
    private final RuleConditionEvaluator evaluator;

    public FaultRuleEngine(DeviceTypeRegistryProperties registry, RuleConditionEvaluator evaluator) {
        this.registry = registry;
        this.evaluator = evaluator;
    }

    /**
     * @param deviceType     本平台设备类型(如 smart_bulb)
     * @param state          设备 state(模拟器 /devices/{id}/data)
     * @param runningStatus  模拟器 running_status(running/stopped)
     */
    public FaultVerdict evaluate(String deviceType, Map<String, Object> state, String runningStatus) {
        DeviceTypeRegistryProperties.DeviceTypeSpec spec = registry.specOf(deviceType);
        if (spec == null || spec.faultRules() == null) {
            return FaultVerdict.normal();
        }
        Map<String, Object> context = new HashMap<>(state == null ? Map.of() : state);
        context.put("running_status", runningStatus);
        return FaultVerdict.evaluate(spec.faultRules(), context, evaluator);
    }
}
