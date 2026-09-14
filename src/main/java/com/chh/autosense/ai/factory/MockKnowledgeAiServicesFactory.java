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
                handler.onPartialResponse(reply);
                handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(reply)).build());
            }
        };
    }

    @Bean
    public ChatModel knowledgeMockChatModel() {
        return new ChatModel() {
            private final ObjectMapper json = new ObjectMapper();
            @Override public ChatResponse doChat(ChatRequest request) {
                try {
                    String text = ((UserMessage) request.messages().getLast()).singleText();
                    var input = json.readTree(text.substring(0, text.lastIndexOf("}") + 1));
                    Map<String, Object> output = new LinkedHashMap<>();
                    if (input.has("evidence")) {
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
