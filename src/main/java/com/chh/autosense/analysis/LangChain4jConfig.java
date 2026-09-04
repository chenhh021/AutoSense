package com.chh.autosense.analysis;

import com.chh.autosense.config.LlmProperties;
import com.chh.autosense.domain.enums.Intent;
import com.chh.autosense.routing.DirectAnswerer;
import com.chh.autosense.routing.IntentClassifier;
import com.chh.autosense.session.memory.ChatMemoryFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.V;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * LangChain4j 生产装配(research R2/R3/R15/R18,章程原则 IV/V):
 * 模型参数全部经 application.yaml 注入;LLM 仅负责意图分类/语义分析/诊断推理/直答,
 * 修复动作不走 tool-calling,只能由状态机调用适配器白名单。
 * 对话记忆:memoryId=sessionId,MessageWindowChatMemory(最近 20 条,R15)。
 * 仅当 autosense.llm.mode=real 时装配。
 */
@Configuration
@ConditionalOnProperty(name = "autosense.llm.mode", havingValue = "real")
public class LangChain4jConfig {

    private static final Logger log = LoggerFactory.getLogger(LangChain4jConfig.class);

    @Bean
    public OpenAiChatModel chatModel(LlmProperties props) {
        return OpenAiChatModel.builder()
                .baseUrl(props.baseUrl())
                .apiKey(props.apiKey())
                .modelName(props.modelName())
                .temperature(props.temperature())
                .timeout(Duration.ofSeconds(props.timeoutSeconds()))
                .build();
    }

    @Bean
    public OpenAiStreamingChatModel streamingChatModel(LlmProperties props) {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(props.baseUrl())
                .apiKey(props.apiKey())
                .modelName(props.modelName())
                .temperature(props.temperature())
                .timeout(Duration.ofSeconds(props.timeoutSeconds()))
                .build();
    }

    /** LLM 语义分析 AiService:输出 JSON,由 Jackson 映射为 ProblemAnalysis */
    interface ProblemAnalysisAiService {
        @dev.langchain4j.service.UserMessage("""
                从用户问题中提取设备类型与问题表现,只输出 JSON:
                {"deviceType":"smart_bulb|null",
                 "symptom":"问题表现","reproduction":"复现方式或null",
                 "sufficient":true|false,"clarifyQuestion":"信息不足时的追问或null"}
                用户输入:{{text}}
                """)
        String analyze(@V("text") String text);
    }

    /** LLM 诊断推理 AiService:结合快照输出 JSON 结论 */
    interface DiagnosisAiService {
        @dev.langchain4j.service.UserMessage("""
                结合用户问题与设备诊断快照,推断问题并只输出 JSON:
                {"problemSummary":"问题摘要","conclusionText":"面向用户的结论",
                 "likelyAutoFixable":true|false}
                用户问题:{{text}}
                问题表现:{{symptom}}
                诊断快照:{{diagnostics}}
                """)
        String diagnose(@V("text") String text, @V("symptom") String symptom,
                        @V("diagnostics") String diagnostics);
    }

    @Bean
    public ProblemAnalyzer langChain4jProblemAnalyzer(OpenAiChatModel chatModel, ObjectMapper om,
                                                      ChatMemoryFactory memoryFactory) {
        return (text, sessionId) -> {
            try {
                List<ChatMessage> messages = new ArrayList<>(
                        memoryFactory.forSession(sessionId).messages());
                messages.add(UserMessage.from("""
                        从用户问题中提取设备类型与问题表现,只输出 JSON:
                        {"deviceType":"smart_bulb|null","symptom":"问题表现",
                         "reproduction":"复现方式或null","sufficient":true|false,
                         "clarifyQuestion":"信息不足时的追问或null"}
                        用户输入:%s""".formatted(text)));
                String json = stripFence(chatModel.chat(messages).aiMessage().text());
                return om.readValue(json, ProblemAnalysis.class);
            } catch (Exception e) {
                return new ProblemAnalysis(null, null, null, false,
                        "没能理解您的问题,请换一种方式描述设备与故障现象。");
            }
        };
    }

    @Bean
    public DiagnosisReasoner langChain4jDiagnosisReasoner(OpenAiChatModel chatModel,
                                                          ObjectMapper om,
                                                          ChatMemoryFactory memoryFactory) {
        return (text, symptom, diagnostics, sessionId) -> {
            try {
                List<ChatMessage> messages = new ArrayList<>(
                        memoryFactory.forSession(sessionId).messages());
                messages.add(UserMessage.from("""
                        结合用户问题与设备诊断快照,推断问题并只输出 JSON:
                        {"problemSummary":"问题摘要","conclusionText":"面向用户的结论",
                         "likelyAutoFixable":true|false}
                        用户问题:%s
                        问题表现:%s
                        诊断快照:%s""".formatted(text, symptom,
                        om.writeValueAsString(diagnostics == null ? Map.of() : diagnostics))));
                String json = stripFence(chatModel.chat(messages).aiMessage().text());
                return om.readValue(json, DiagnosisConclusion.class);
            } catch (Exception e) {
                return new DiagnosisConclusion("诊断服务暂不可用",
                        "诊断服务暂时不可用,请稍后再试。", false);
            }
        };
    }

    /** LLM 意图分类(FR-019/R13):只输出意图枚举名。 */
    @Bean
    public IntentClassifier langChain4jIntentClassifier(OpenAiChatModel chatModel,
                                                        ChatMemoryFactory memoryFactory) {
        return (text, sessionId) -> {
            try {
                List<ChatMessage> messages = new ArrayList<>(
                        memoryFactory.forSession(sessionId).messages());
                messages.add(UserMessage.from("""
                        将用户输入分类,只输出一个枚举词:
                        COMMON_SENSE(常识问题) / DEVICE_ACTION(设备故障或希望操作设备) /
                        MODEL_SPECIFIC(询问具体型号能力/参数) / AFTERSALES_QUERY(查询售后网点) /
                        UNCLEAR(无法判断)
                        用户输入:%s""".formatted(text)));
                String raw = chatModel.chat(messages).aiMessage().text();
                return Intent.valueOf(stripFence(raw).trim());
            } catch (Exception e) {
                // 分类失败 = 不确定:追问澄清,不得触碰设备(FR-019)
                return Intent.UNCLEAR;
            }
        };
    }

    /** LLM 流式直答(FR-019 路由 1/3 + FR-021 token 流)。 */
    @Bean
    public DirectAnswerer langChain4jDirectAnswerer(OpenAiStreamingChatModel streamingChatModel,
                                                    ChatMemoryFactory memoryFactory) {
        return (question, sessionId, onToken) -> {
            dev.langchain4j.memory.ChatMemory memory = memoryFactory.forSession(sessionId);
            List<ChatMessage> messages = new ArrayList<>(memory.messages());
            UserMessage userMessage = UserMessage.from("""
                    你是智能家居助手,用简洁中文回答用户问题。
                    用户问题:%s""".formatted(question));
            messages.add(userMessage);
            StringBuilder full = new StringBuilder();
            CompletableFuture<String> result = new CompletableFuture<>();
            dev.langchain4j.model.chat.response.StreamingChatResponseHandler handler =
                    new dev.langchain4j.model.chat.response.StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String partialResponse) {
                    if (result.isDone()) {
                        return;
                    }
                    full.append(partialResponse);
                    onToken.accept(partialResponse);
                }

                @Override
                public void onCompleteResponse(
                        dev.langchain4j.model.chat.response.ChatResponse completeResponse) {
                    String answer = full.toString();
                    if (answer.isBlank()) {
                        answer = "模型未返回有效内容,请稍后再试。";
                        onToken.accept(answer);
                    }
                    try {
                        // 记忆落库:用户问题 + 完整回答(R15)
                        memory.add(userMessage);
                        memory.add(AiMessage.from(answer));
                    } catch (Exception e) {
                        // 回答已经生成,记忆写入失败不应吞掉本次回复。
                        log.warn("LLM 对话记忆写入失败 session={}: {}", sessionId, e.getMessage());
                    }
                    result.complete(answer);
                }

                @Override
                public void onError(Throwable error) {
                    if (result.isDone()) {
                        return;
                    }
                    log.warn("LLM 流式直答失败 session={}: {}", sessionId,
                            error == null ? "unknown" : error.getMessage());
                    String fallback = "回答生成遇到问题,请稍后再试。";
                    onToken.accept(fallback);
                    result.complete(fallback);
                }
            };
            try {
                streamingChatModel.chat(messages, handler);
            } catch (RuntimeException e) {
                handler.onError(e);
            }
            return result;
        };
    }

    private static String stripFence(String json) {
        if (json == null) {
            return "{}";
        }
        return json.replaceAll("(?s)```json\\s*", "").replaceAll("```", "").trim();
    }
}
