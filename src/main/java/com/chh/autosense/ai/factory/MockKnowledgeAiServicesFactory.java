package com.chh.autosense.ai.factory;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.*;

/** Local models for the SAME public factory and SDK RAG path. Never a separate proxy cache. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "autosense.llm.mode", havingValue = "mock")
public class MockKnowledgeAiServicesFactory {
    @Bean
    public StreamingChatModel knowledgeMockStreamingChatModel() {
        return new StreamingChatModel() {
            @Override public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                String reply = "这是本地模拟回答，请根据可靠的产品资料核对具体参数。";
                try {
                    String text = request.messages().stream().filter(UserMessage.class::isInstance)
                            .map(UserMessage.class::cast).reduce((first, last) -> last).orElseThrow().singleText();
                    var input = new ObjectMapper().readTree(text);
                    if (input.has("queryResult")) reply = deviceQueryReply(input);
                } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
                    // Other existing mock prompts are not necessarily JSON envelopes.
                }
                handler.onPartialResponse(reply);
                handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(reply)).build());
            }
        };
    }

    /** Deterministic fixture for the query-answer prompt; production selection is performed by the model. */
    private static String deviceQueryReply(com.fasterxml.jackson.databind.JsonNode input) {
        String question = input.path("text").asText() + " " + input.path("instruction").asText();
        if (question.contains("它") || question.contains("那个")) question = input.path("history") + " " + question;
        var labels = Map.ofEntries(Map.entry("target_temperature", "设定温度"), Map.entry("current_temperature", "当前室温"),
                Map.entry("brightness", "亮度"), Map.entry("power", "电源"), Map.entry("mode", "运行模式"),
                Map.entry("fan_speed", "风速"), Map.entry("online", "在线状态"), Map.entry("pm25", "PM2.5"),
                Map.entry("filter_life", "滤网剩余寿命"), Map.entry("firmware_version", "固件版本"));
        var selected = new LinkedHashSet<String>();
        if (question.contains("设定") || question.contains("目标温度") || question.contains("设置温度")) selected.add("target_temperature");
        if (question.contains("室温") || question.contains("当前温度") || question.contains("环境温度")) selected.add("current_temperature");
        if (question.contains("温度") && selected.isEmpty()) selected.addAll(List.of("current_temperature", "target_temperature"));
        for (var entry : labels.entrySet())
            if (!entry.getKey().contains("temperature") && question.contains(entry.getValue())) selected.add(entry.getKey());
        boolean all = selected.isEmpty() && (question.contains("状态") || question.contains("全部") || question.contains("参数") || question.contains("properties"));
        var values = input.path("queryResult").path("values");
        var sentences = new ArrayList<String>();
        values.fields().forEachRemaining(entry -> {
            String field = entry.getKey().substring(entry.getKey().lastIndexOf('.') + 1);
            if (!all && !selected.contains(field)) return;
            var value = entry.getValue().path("value");
            String rendered = value.isBoolean() ? (field.equals("online") ? (value.booleanValue() ? "在线" : "离线")
                    : (value.booleanValue() ? "开启" : "关闭")) : value.asText();
            rendered = Map.of("AUTO", "自动", "OFF", "关闭", "RUNNING", "运行中", "STOPPED", "已停止")
                    .getOrDefault(rendered, rendered);
            String unit = entry.getValue().path("unit").asText("");
            sentences.add(labels.getOrDefault(field, field) + "为 " + rendered + (unit.equals("°C") ? " 度" : unit) + "。");
        });
        return sentences.isEmpty() ? "当前查询结果没有所需的信息。" : String.join("\n", sentences);
    }

    @Bean
    public ChatModel knowledgeMockChatModel() {
        return new ChatModel() {
            private final ObjectMapper json = new ObjectMapper();
            @Override public ChatResponse doChat(ChatRequest request) {
                try {
                    String text = request.messages().stream().filter(UserMessage.class::isInstance)
                            .map(UserMessage.class::cast).reduce((first, last) -> last).orElseThrow().singleText();
                    var input = json.readTree(text.substring(0, text.lastIndexOf("}") + 1));
                    Map<String, Object> output = new LinkedHashMap<>();
                    boolean planner = input.has("devices") && input.has("originalRequest");
                    if (planner) {
                        var toolResult = request.messages().stream()
                                .filter(dev.langchain4j.data.message.ToolExecutionResultMessage.class::isInstance)
                                .map(dev.langchain4j.data.message.ToolExecutionResultMessage.class::cast)
                                .filter(result -> "deviceList".equals(result.toolName())).reduce((first, last) -> last);
                        if (toolResult.isPresent()) {
                            ((com.fasterxml.jackson.databind.node.ObjectNode) input).set("devices", json.readTree(toolResult.get().text()));
                        } else if (!input.path("devices").has("devices") && MockPlanResponses.needsDevices(input)
                                && request.toolSpecifications() != null && request.toolSpecifications().stream()
                                .anyMatch(tool -> "deviceList".equals(tool.name()))) {
                            return ChatResponse.builder().aiMessage(AiMessage.from(
                                    dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                                            .id("mock-device-list").name("deviceList")
                                            .arguments(json.writeValueAsString(Map.of("userId", input.path("devices").path("userId").asText(""))))
                                            .build())).build();
                        }
                        output = MockPlanResponses.plan(input);
                    } else if (input.has("diagnostics")) {
                        output.put("problemSummary", "设备故障诊断");
                        output.put("conclusionText", "本地模拟诊断：请结合本步骤提供的证据和知识资料核对原因，未执行任何设备操作。");
                        output.put("likelyAutoFixable", false);
                    } else if (input.has("evidence")) {
                        var sources = new LinkedHashSet<String>();
                        input.path("evidence").forEach(e -> sources.add(e.path("sourceId").asText()));
                        if (sources.isEmpty()) throw new IllegalStateException("Mock enhanced answer requires evidence");
                        output.put("answer", "本地模拟：以下回答依据本轮检索资料，具体参数请核对对应型号来源。");
                        output.put("sourceIds", sources);
                        output.put("status", "ANSWERED");
                    } else if (input.has("catalog")) {
                        String question = input.path("text").asText();
                        String lower = question.toLowerCase(Locale.ROOT);
                        Set<String> types = new LinkedHashSet<>();
                        var catalog = input.path("catalog");
                        catalog.path("aliases").fields().forEachRemaining(e -> {
                            if (lower.contains(e.getKey().toLowerCase(Locale.ROOT))) types.add(e.getValue().asText());
                        });
                        catalog.path("productsByType").fields().forEachRemaining(e -> {
                            if (lower.contains(e.getKey().toLowerCase(Locale.ROOT))) types.add(e.getKey());
                            e.getValue().forEach(product -> {
                                String model = product.asText().split("-", 2)[1];
                                if (lower.contains(model.toLowerCase(Locale.ROOT))) types.add(e.getKey());
                            });
                        });
                        output.put("deviceType", types.size() == 1 ? types.iterator().next() : null);
                        output.put("brand", null);
                        var model = java.util.regex.Pattern.compile("(?i)[a-z][a-z0-9._-]*[0-9][a-z0-9._-]*").matcher(question);
                        String modelName = model.find() ? model.group() : null;
                        output.put("model", modelName);
                        output.put("queryText", question);
                        output.put("missingInformation", modelName == null ? List.of("MODEL") : List.of());
                        output.put("version", null);
                        output.put("environment", null);
                    } else {
                        output.put("deviceType", null);
                        output.put("symptom", null);
                        output.put("reproduction", null);
                        output.put("sufficient", false);
                        output.put("clarifyQuestion", "请补充问题信息。");
                    }
                    return ChatResponse.builder().aiMessage(AiMessage.from(json.writeValueAsString(output))).build();
                } catch (Exception e) {
                    throw new IllegalStateException("Mock model input is invalid");
                }
            }
        };
    }
}
