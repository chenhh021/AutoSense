package com.chh.autosense.ai.factory;

import com.chh.autosense.domain.enums.DeviceType;
import java.util.*;

/** Deterministic model output for explicit LLM_MODE=mock; still parsed by the same AI Service proxy. */
final class MockPlanResponses {
    private MockPlanResponses() { }
    static boolean needsDevices(com.fasterxml.jackson.databind.JsonNode input) {
        String original = input.path("originalRequest").asText();
        var proposal = plan(original.isBlank() ? input.path("text").asText() : original);
        if (!"PLAN".equals(proposal.get("outcome"))) proposal = plan(input.path("text").asText());
        if (!(proposal.get("steps") instanceof List<?> steps)) return false;
        return steps.stream().anyMatch(value -> value instanceof Map<?, ?> step
                && (Set.of("DEVICE_QUERY", "DEVICE_CONTROL").contains(step.get("type"))
                || step.get("parameters") instanceof Map<?, ?> parameters && "DEVICE_CONTEXT".equals(parameters.get("answerMode"))));
    }
    static Map<String, Object> plan(com.fasterxml.jackson.databind.JsonNode input) {
        String original = input.path("originalRequest").asText();
        String text = input.path("text").asText();
        boolean followup = !original.equals(text);
        var proposal = plan(original.isBlank() ? text : original);
        if (followup && "CLARIFY".equals(proposal.get("outcome"))) proposal = plan(text);
        if (!"PLAN".equals(proposal.get("outcome"))) return proposal;
        var devices = new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        input.path("devices").path("devices").forEach(devices::add);
        @SuppressWarnings("unchecked") var steps = (List<Map<String, Object>>) proposal.get("steps");
        for (var step : steps) {
            if (!Set.of("DEVICE_QUERY", "DEVICE_CONTROL").contains(step.get("type"))) continue;
            if (step.get("inputBindings") instanceof Map<?, ?> bindings && bindings.containsKey("deviceRef")) continue;
            var candidates = select(devices, original);
            if (followup) {
                // Number selection is meaningful only against the same ordered candidates shown in the question.
                try {
                    int index = Integer.parseInt(text.trim());
                    if (index >= 1 && index <= candidates.size()) candidates = List.of(candidates.get(index - 1));
                } catch (NumberFormatException ignored) {
                    candidates = select(candidates, text);
                }
            }
            if (candidates.size() != 1) {
                var choices = candidates;
                String question = candidates.isEmpty() ? "未找到对应的已绑定设备，请说明要查询哪台已绑定设备。"
                        : "请选择设备：" + java.util.stream.IntStream.range(0, candidates.size())
                        .mapToObj(i -> (i + 1) + ". " + candidatesName(choices, i)).collect(java.util.stream.Collectors.joining("；"));
                return Map.of("outcome", "CLARIFY", "steps", List.of(), "clarifyQuestion", question);
            }
            @SuppressWarnings("unchecked") var parameters = new LinkedHashMap<>((Map<String, Object>) step.get("parameters"));
            parameters.put("deviceRef", candidates.getFirst().path("id").longValue());
            step.put("parameters", parameters);
        }
        return proposal;
    }

    private static String candidatesName(List<com.fasterxml.jackson.databind.JsonNode> values, int index) {
        var d = values.get(index);
        return d.path("name").asText() + "（" + d.path("deviceModel").asText() + "，SN：" + d.path("sn").asText() + "）";
    }

    private static List<com.fasterxml.jackson.databind.JsonNode> select(List<com.fasterxml.jackson.databind.JsonNode> devices, String text) {
        var exact = devices.stream().filter(d -> !d.path("sn").asText().isBlank() && text.contains(d.path("sn").asText())).toList();
        if (!exact.isEmpty()) return exact;
        exact = devices.stream().filter(d -> !d.path("name").asText().isBlank() && text.contains(d.path("name").asText())).toList();
        if (!exact.isEmpty()) return exact;
        var filtered = devices;
        for (String location : List.of("客厅", "卧室", "厨房", "书房", "阳台"))
            if (text.contains(location)) filtered = filtered.stream().filter(d -> d.path("name").asText().contains(location)).toList();
        var type = text.contains("灯") ? DeviceType.SMART_BULB
                : text.contains("空调") ? DeviceType.AIR_CONDITIONER
                : text.contains("净化器") ? DeviceType.AIR_PURIFIER : null;
        if (type != null) filtered = filtered.stream().filter(d -> type ==
                DeviceType.fromCode(d.path("deviceType").asText())).toList();
        return filtered;
    }
    static Map<String, Object> plan(String text) {
        if (text.isBlank() || text.equals("帮帮我")) return Map.of("outcome", "CLARIFY", "steps", List.of(), "clarifyQuestion", "请补充要咨询或操作的设备问题。");
        if (text.contains("天气") || text.contains("股票")) return Map.of("outcome", "OUT_OF_SCOPE", "steps", List.of());
        var steps = new ArrayList<Map<String, Object>>();
        boolean basic = text.contains("设备列表") || text.contains("我的设备") || text.contains("哪些设备")
                || text.contains("设备型号") || text.contains("固件") || text.contains("在线") || text.contains("离线")
                || text.contains("SN") || text.contains("序列号");
        boolean runtime = text.contains("亮度") || text.contains("温度") || text.contains("PM2.5") || text.contains("功率")
                || text.contains("诊断") || text.contains("故障") || text.contains("设置") || text.contains("打开") || text.contains("关闭");
        if (basic && !runtime) {
            var answer = step("s1", "KNOWLEDGE_CONSULT", text, Map.of("answerMode", "DEVICE_CONTEXT"));
            answer.put("requiresKnowledgeBase", false);
            return Map.of("outcome", "PLAN", "steps", List.of(answer));
        }
        boolean diagnosis = text.contains("诊断") || text.contains("故障") || text.contains("不亮") || text.contains("售后");
        boolean query = diagnosis && !text.contains("售后") || text.contains("查询") || text.contains("我的设备") || text.contains("当前") || text.contains("状态") || text.contains("低于");
        boolean control = text.contains("打开") || text.contains("关闭") || text.contains("调到") || text.contains("设置") || text.contains("调亮");
        if (query) steps.add(step("s1", "DEVICE_QUERY", text, Map.of("action", diagnosis ? "diagnostic_snapshot" : "state")));
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
