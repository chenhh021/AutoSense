package com.chh.autosense.core.device.rule;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 默认规则条件求值器(R12):故意保持小而确定——只支持单条件简单比较,
 * 不支持任意表达式求值(避免注入风险);不支持的条件一律不命中并在日志可观测。
 */
@Component
public class SimpleRuleConditionEvaluator implements RuleConditionEvaluator {

    private static final Pattern CMP = Pattern.compile(
            "^([a-zA-Z_][a-zA-Z0-9_]*)\\s*(==|!=|<=|>=|<|>)\\s*(.+)$");

    @Override
    public boolean matches(String when, Map<String, Object> state) {
        if (when == null || when.isBlank() || state == null) {
            return false;
        }
        Matcher m = CMP.matcher(when.trim());
        if (!m.matches()) {
            return false;
        }
        String field = m.group(1);
        String op = m.group(2);
        String rawExpected = m.group(3).trim();
        Object actual = state.get(field);

        if ("null".equals(rawExpected)) {
            return "==".equals(op) ? actual == null : "!=".equals(op) && actual != null;
        }
        if (actual == null) {
            return false;
        }
        if ("true".equals(rawExpected) || "false".equals(rawExpected)) {
            boolean expected = Boolean.parseBoolean(rawExpected);
            boolean actualBool = actual instanceof Boolean b ? b
                    : Boolean.parseBoolean(String.valueOf(actual));
            return "==".equals(op) ? actualBool == expected : "!=".equals(op) && actualBool != expected;
        }
        if (rawExpected.startsWith("'") && rawExpected.endsWith("'")) {
            String expected = rawExpected.substring(1, rawExpected.length() - 1);
            int cmp = String.valueOf(actual).compareTo(expected);
            return switch (op) {
                case "==" -> cmp == 0;
                case "!=" -> cmp != 0;
                default -> false;
            };
        }
        try {
            double expected = Double.parseDouble(rawExpected);
            double actualNum = actual instanceof Number n ? n.doubleValue()
                    : Double.parseDouble(String.valueOf(actual));
            return switch (op) {
                case "==" -> actualNum == expected;
                case "!=" -> actualNum != expected;
                case "<" -> actualNum < expected;
                case "<=" -> actualNum <= expected;
                case ">" -> actualNum > expected;
                case ">=" -> actualNum >= expected;
                default -> false;
            };
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
