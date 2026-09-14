package com.chh.autosense.core.routing;

import com.chh.autosense.domain.dto.KnowledgeDirectAnswerContext;
import com.chh.autosense.domain.enums.AssistantCapability;
import com.chh.autosense.domain.enums.KnowledgeDirectAnswerReason;
import com.chh.autosense.exception.ApiException;
import com.chh.autosense.exception.ErrorCode;
import com.chh.autosense.exception.KnowledgeInsufficientException;
import com.chh.autosense.ai.factory.AiFailureMapping;
import com.chh.autosense.ai.model.KnowledgeAnswer;
import com.chh.autosense.ai.model.KnowledgeQueryAnalysis;
import com.chh.autosense.ai.model.enums.KnowledgeAnswerStatus;
import com.chh.autosense.ai.rag.KnowledgeEmbeddingStore.Catalog;
import com.chh.autosense.ai.rag.KnowledgeQueryRouter;
import com.chh.autosense.config.KnowledgeProperties;
import com.chh.autosense.domain.dto.KnowledgeAnswerRequest;
import com.chh.autosense.service.knowledge.UserAiServiceCache;
import com.chh.autosense.utils.AiCallLog;
import com.chh.autosense.utils.PromptInputEncoder;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.service.Result;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** Knowledge-only orchestration; device access and mutable conversation state stay outside. */
@Component
public class KnowledgeCapabilityHandler implements AssistantCapabilityHandler {
    private final DirectAnswerer direct;
    private final UserAiServiceCache cache;
    private final PromptInputEncoder encoder;
    private final Catalog catalog;
    private final KnowledgeProperties properties;

    public KnowledgeCapabilityHandler(DirectAnswerer direct, UserAiServiceCache cache,
            PromptInputEncoder encoder, Catalog catalog, KnowledgeProperties properties) {
        this.direct = direct;
        this.cache = cache;
        this.encoder = encoder;
        this.catalog = catalog;
        this.properties = properties;
    }

    @Override public AssistantCapability capability() { return AssistantCapability.KNOWLEDGE; }

    @Override public CompletionStage<CapabilityResult> handle(CapabilityRequest request, Consumer<String> visibleText) {
        try {
            checkDeadline(request);
            if (Boolean.FALSE.equals(request.requiresKnowledgeBase()))
                return direct(request, KnowledgeDirectAnswerReason.COMMON_SENSE, visibleText);
            if (!Boolean.TRUE.equals(request.requiresKnowledgeBase())) throw new IllegalArgumentException("Knowledge routing flag is missing");
            var services = cache.getOrCreate(request.user());
            KnowledgeQueryAnalysis analysis;
            AiCallLog analyze = AiCallLog.start("queryAnalysis");
            try {
                String history = encoder.history(request.history());
                String text = encoder.text(request.content());
                String types = encoder.knowledgeCatalog(catalog);
                checkDeadline(request);
                analyze.phase(AiCallLog.Phase.MODEL_INVOCATION);
                analysis = services.analysis().analyzeKnowledge(history, text, types);
                analyze.phase(AiCallLog.Phase.OUTPUT_VALIDATION);
                if (analysis == null || analysis.queryText() == null || analysis.queryText().isBlank())
                    throw new IllegalStateException("Invalid knowledge analysis");
                checkDeadline(request);
                analyze.completed();
            } catch (RuntimeException e) { analyze.failed(e); throw e; }
            String type = catalog.normalizeType(analysis.deviceType());
            if (type == null) return direct(request, KnowledgeDirectAnswerReason.TYPE_UNKNOWN, visibleText);
            var scope = new KnowledgeAnswerRequest.Scope(type, analysis.brand(), analysis.model(),
                    analysis.missingInformation(), analysis.version(), analysis.environment());
            var envelope = new KnowledgeAnswerRequest(encoder.visibleHistory(request.history()), request.content(),
                    analysis.queryText(), scope, request.deadline().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
            AiCallLog answer = AiCallLog.start("enhancedAnswer");
            String display;
            try {
                String input = encoder.knowledgeRequest(envelope);
                checkDeadline(request);
                answer.phase(AiCallLog.Phase.MODEL_INVOCATION);
                Result<KnowledgeAnswer> result = services.enhanced().answerKnowledge(input);
                answer.phase(AiCallLog.Phase.OUTPUT_VALIDATION);
                checkDeadline(request);
                display = display(result, scope);
                // Synchronous enhanced output becomes SSE chunks only after full validation.
                answer.phase(AiCallLog.Phase.TOKEN_CALLBACK);
                for (int offset = 0; offset < display.length();) {
                    checkDeadline(request);
                    int end = display.offsetByCodePoints(offset, Math.min(64, display.codePointCount(offset, display.length())));
                    visibleText.accept(display.substring(offset, end));
                    offset = end;
                }
                checkDeadline(request);
                answer.completed();
            } catch (KnowledgeInsufficientException insufficient) {
                answer.skipped(insufficient.reason().name());
                return direct(request, insufficient.reason(), visibleText);
            } catch (RuntimeException e) { answer.failed(e); throw e; }
            return CompletableFuture.completedFuture(CapabilityResult.answer(display));
        } catch (RuntimeException e) { return CompletableFuture.failedFuture(failure(e)); }
    }

    private CompletionStage<CapabilityResult> direct(CapabilityRequest request, KnowledgeDirectAnswerReason reason,
            Consumer<String> sink) {
        checkDeadline(request);
        String prefix = switch (reason) {
            case COMMON_SENSE -> "";
            case TYPE_UNKNOWN -> "无法确定设备类型，缺少可靠的专门资料，以下基于一般知识回答。\n\n";
            case NO_MATCH, LOW_RELEVANCE -> "未检索到足够相关的资料，以下基于一般知识回答，具体参数请核对对应型号说明书。\n\n";
        };
        // Prefix is emitted with the first real token, so stream construction failures expose no answer.
        var started = new java.util.concurrent.atomic.AtomicBoolean();
        return direct.answerKnowledge(request, new KnowledgeDirectAnswerContext(reason), token -> {
            checkDeadline(request);
            if (started.compareAndSet(false, true) && !prefix.isEmpty()) sink.accept(prefix);
            sink.accept(token);
        }).handle((body, error) -> {
            checkDeadline(request);
            if (error != null) throw new CompletionException(failure(error));
            if (body == null || body.isBlank() || !started.get()) throw new IllegalStateException("Empty knowledge answer");
            return CapabilityResult.answer(prefix + body);
        });
    }

    private String display(Result<KnowledgeAnswer> result, KnowledgeAnswerRequest.Scope scope) {
        if (result == null || result.content() == null || result.sources() == null || result.sources().isEmpty())
            throw new IllegalStateException("Knowledge answer sources are missing");
        KnowledgeAnswer output = result.content();
        if (output.answer() == null || output.answer().isBlank() || output.status() == null || output.sourceIds() == null
                || output.sourceIds().isEmpty() || new HashSet<>(output.sourceIds()).size() != output.sourceIds().size())
            throw new IllegalStateException("Invalid knowledge answer");
        Map<String, Content> available = new LinkedHashMap<>();
        for (Content content : result.sources()) {
            KnowledgeQueryRouter.validateContent(content, scope.deviceType());
            if (((Number) content.metadata().get(ContentMetadata.SCORE)).doubleValue() < properties.retrieval().minScore())
                throw new IllegalStateException("Knowledge source relevance is insufficient");
            String id = content.textSegment().metadata().getString("sourceId");
            Content prior = available.putIfAbsent(id, content);
            if (prior != null && !prior.textSegment().metadata().getString("documentHash")
                    .equals(content.textSegment().metadata().getString("documentHash")))
                throw new IllegalStateException("Conflicting knowledge source identity");
        }
        List<Content> cited = output.sourceIds().stream().map(id -> {
            if (id == null || !available.containsKey(id)) throw new IllegalStateException("Knowledge citation is outside this request");
            return available.get(id);
        }).toList();
        var products = cited.stream().map(c -> c.textSegment().metadata().getString("productKey")).distinct().toList();
        boolean notice = !scope.missingInformation().isEmpty() || cited.stream().anyMatch(c -> !sameModel(scope, c));
        StringBuilder text = new StringBuilder();
        if (notice) text.append("没有当前型号设备信息，本次回答依据型号：").append(String.join("、", products)).append("。\n\n");
        if (output.status() == KnowledgeAnswerStatus.CONFLICT) text.append("资料存在冲突，请核对以下依据与适用条件。\n\n");
        text.append(output.answer()).append("\n\n来源：\n");
        for (Content source : cited) {
            var m = source.textSegment().metadata();
            text.append("- ").append(m.getString("productKey")).append(" — ").append(markdown(m.getString("sourceName")))
                    .append("；sourceId: `").append(m.getString("sourceId")).append("`；documentHash: `")
                    .append(m.getString("documentHash")).append("`\n");
        }
        return text.toString().stripTrailing();
    }

    private boolean sameModel(KnowledgeAnswerRequest.Scope scope, Content source) {
        if (scope.model() == null || scope.model().isBlank()) return false;
        var metadata = source.textSegment().metadata();
        String model = alias(scope.model(), properties.modelAliases());
        if (model.equals(normalize(metadata.getString("productKey")))) return true;
        if (!model.equals(normalize(metadata.getString("model")))) return false;
        if (scope.brand() != null && !scope.brand().isBlank())
            return alias(scope.brand(), properties.brandAliases()).equals(normalize(metadata.getString("brand")));
        // A bare model is unambiguous only if exactly one catalog product owns that name.
        return catalog.productsByType().getOrDefault(scope.deviceType(), List.of()).stream()
                .filter(p -> normalize(p.substring(p.indexOf('-') + 1)).equals(model)).count() == 1;
    }

    private static String alias(String value, Map<String, String> aliases) {
        return normalize(aliases.getOrDefault(normalize(value), value));
    }
    private static String normalize(String value) {
        return Normalizer.normalize(value.trim(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }
    private static String markdown(String value) {
        StringBuilder safe = new StringBuilder();
        value.codePoints().filter(c -> !Character.isISOControl(c)).forEach(c -> {
            if ("\\`*_{}[]()<>!|#&".indexOf(c) >= 0) safe.append('\\');
            safe.appendCodePoint(c);
        });
        return safe.toString();
    }
    private static ApiException failure(Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) error = error.getCause();
        if (error instanceof ApiException api) return api;
        return new ApiException(AiFailureMapping.isTransportFailure(error) ? ErrorCode.AI_SERVICE_UNAVAILABLE : ErrorCode.INTERNAL_ERROR,
                "知识服务暂不可用，请稍后重试。");
    }

    static void checkDeadline(CapabilityRequest request) {
        if (Thread.currentThread().isInterrupted() || request.deadline() == null || !LocalDateTime.now().isBefore(request.deadline()))
            throw new ApiException(ErrorCode.REQUEST_TIMEOUT, "知识咨询处理超时，请重试。");
    }
}
