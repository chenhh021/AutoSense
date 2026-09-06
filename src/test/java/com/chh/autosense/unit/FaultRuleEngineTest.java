package com.chh.autosense.unit;

import com.chh.autosense.config.DeviceTypeRegistryProperties.FaultRuleSpec;
import com.chh.autosense.core.device.rule.FaultVerdict;
import com.chh.autosense.core.device.rule.SimpleRuleConditionEvaluator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 故障规则引擎单测(T011):三类结论 + 无命中 NORMAL + 按序匹配。
 */
class FaultRuleEngineTest {

    private final SimpleRuleConditionEvaluator evaluator = new SimpleRuleConditionEvaluator();

    private static FaultRuleSpec rule(String name, String when, String conclusion,
                                      String action, Map<String, Object> params) {
        return new FaultRuleSpec(name, when, conclusion, action, params, null, "原因-" + name);
    }

    @Test
    void 亮度低于阈值命中可自动修复() {
        List<FaultRuleSpec> rules = List.of(
                rule("亮度过低", "brightness < 5", "AUTO_REPAIRABLE", "set_brightness",
                        Map.of("brightness", 80)));
        FaultVerdict v = FaultVerdict.evaluate(rules, Map.of("brightness", 0), evaluator);
        assertThat(v.kind()).isEqualTo(FaultVerdict.Kind.AUTO_REPAIRABLE);
        assertThat(v.actionCode()).isEqualTo("set_brightness");
        assertThat(v.actionParams()).containsEntry("brightness", 80);
    }

    @Test
    void 停止状态命中可自动修复_启动动作() {
        List<FaultRuleSpec> rules = List.of(
                rule("设备已停止", "running_status == 'stopped'", "AUTO_REPAIRABLE", "start", null));
        FaultVerdict v = FaultVerdict.evaluate(rules, Map.of("running_status", "stopped"), evaluator);
        assertThat(v.kind()).isEqualTo(FaultVerdict.Kind.AUTO_REPAIRABLE);
        assertThat(v.actionCode()).isEqualTo("start");
    }

    @Test
    void 硬件故障命中人工步骤() {
        List<FaultRuleSpec> rules = List.of(
                new FaultRuleSpec("硬件故障", "hardware_fault == true", "MANUAL_ONLY",
                        null, null, "bulb-hardware-fault", "需人工处理"));
        FaultVerdict v = FaultVerdict.evaluate(rules, Map.of("hardware_fault", true), evaluator);
        assertThat(v.kind()).isEqualTo(FaultVerdict.Kind.MANUAL_ONLY);
        assertThat(v.knowledgeRef()).isEqualTo("bulb-hardware-fault");
    }

    @Test
    void 未知错误命中售后() {
        List<FaultRuleSpec> rules = List.of(
                rule("未知异常", "error != null", "AFTERSALES", null, null));
        FaultVerdict v = FaultVerdict.evaluate(rules, Map.of("error", "E_X"), evaluator);
        assertThat(v.kind()).isEqualTo(FaultVerdict.Kind.AFTERSALES);
        v = FaultVerdict.evaluate(rules, Map.of(), evaluator);
        assertThat(v.kind()).isEqualTo(FaultVerdict.Kind.NORMAL);
    }

    @Test
    void 按序匹配_首个命中生效() {
        List<FaultRuleSpec> rules = List.of(
                rule("先", "brightness < 5", "AFTERSALES", null, null),
                rule("后", "brightness < 5", "AUTO_REPAIRABLE", "set_brightness", null));
        FaultVerdict v = FaultVerdict.evaluate(rules, Map.of("brightness", 1), evaluator);
        assertThat(v.ruleName()).isEqualTo("先");
    }

    @Test
    void 无命中与非法表达式均返回正常() {
        List<FaultRuleSpec> rules = List.of(
                rule("非法", "brightness ~~ 5", "AFTERSALES", null, null),
                rule("不命中", "brightness > 100", "AFTERSALES", null, null));
        FaultVerdict v = FaultVerdict.evaluate(rules, Map.of("brightness", 50), evaluator);
        assertThat(v.kind()).isEqualTo(FaultVerdict.Kind.NORMAL);
    }
}
