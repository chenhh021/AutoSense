package com.chh.autosense.core.device.rule;

import java.util.Map;

/**
 * 规则条件求值器 SPI:将 yaml 中的 when 表达式作用于设备 state。
 * 支持简单比较:"field == 'str'" / "field == true" / "field <op> number"
 * (op ∈ ==, !=, <, <=, >, >=)/ "field != null" / "field == null"。
 */
public interface RuleConditionEvaluator {

    boolean matches(String when, Map<String, Object> state);
}
