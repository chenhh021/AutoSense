package com.chh.autosense.ai.factory;

import java.util.*;

/** Deterministic model output for explicit LLM_MODE=mock; still parsed by the same AI Service proxy. */
final class MockPlanResponses {
    private MockPlanResponses() { }
    static Map<String, Object> plan(String text) {
        if (text.isBlank() || text.equals("帮帮我")) return Map.of("outcome", "CLARIFY", "steps", List.of(), "clarifyQuestion", "请补充要咨询或操作的设备问题。");
        if (text.contains("天气") || text.contains("股票")) return Map.of("outcome", "OUT_OF_SCOPE", "steps", List.of());
        var steps = new ArrayList<Map<String, Object>>();
        boolean diagnosis = text.contains("诊断") || text.contains("故障") || text.contains("不亮") || text.contains("售后");
        boolean query = diagnosis && !text.contains("售后") || text.contains("查询") || text.contains("我的设备") || text.contains("当前") || text.contains("状态") || text.contains("低于");
        boolean control = text.contains("打开") || text.contains("关闭") || text.contains("调到") || text.contains("设置") || text.contains("调亮");
        if (query) steps.add(step("s1", "DEVICE_QUERY", text, Map.of("action", diagnosis ? "diagnostic_snapshot" : text.contains("设备列表") ? "list" : "state")));
        if (diagnosis) {
            var diagnostic = step("s" + (steps.size() + 1), "FAULT_DIAGNOSIS", text, Map.of());
            diagnostic.put("diagnosisMode", text.contains("售后") ? "AFTERSALES" : "DEFAULT");
            if (query) { diagnostic.put("dependsOn", List.of("s1")); diagnostic.put("inputBindings", Map.of("evidence", Map.of("stepId", "s1", "field", "evidence"))); }
            steps.add(diagnostic);
        }
        if (control) {
            boolean brightness = text.contains("调") || text.contains("亮度");
            var params = new LinkedHashMap<String, Object>();
            params.put("action", brightness ? "setBrightness" : "setPower");
            params.put(brightness ? "brightness" : "power", brightness ? 80 : !text.contains("关闭"));
            var command = step("s" + (steps.size() + 1), "DEVICE_CONTROL", text, params);
            if (query) { command.put("dependsOn", List.of("s1")); command.put("inputBindings", Map.of("deviceRef", Map.of("stepId", "s1", "field", "deviceRef"))); }
            if (query && text.contains("低于")) command.put("condition", Map.of("op", "LT", "left", Map.of("stepId", "s1", "field", "brightness"), "right", 30));
            steps.add(command);
            if (text.contains("复检")) {
                var verification = step("s" + (steps.size() + 1), "DEVICE_QUERY", "复检设备状态", Map.of("action", "state"));
                verification.put("dependsOn", List.of(command.get("stepId")));
                verification.put("inputBindings", Map.of("deviceRef", Map.of("stepId", command.get("stepId"), "field", "deviceRef")));
                steps.add(verification);
            }
        }
        if (steps.isEmpty()) {
            var knowledge = step("s1", "KNOWLEDGE_CONSULT", text, Map.of());
            knowledge.put("requiresKnowledgeBase", text.contains("型号") || text.contains("说明") || text.contains("功能") || text.contains("参数") || text.matches(".*[A-Za-z]+[-0-9].*"));
            steps.add(knowledge);
        }
        return Map.of("outcome", "PLAN", "steps", steps);
    }
    private static Map<String, Object> step(String id, String type, String text, Map<String, Object> parameters) {
        var step = new LinkedHashMap<String, Object>();
        step.put("stepId", id); step.put("type", type); step.put("instruction", text); step.put("targetHint", text); step.put("parameters", parameters);
        return step;
    }
}
