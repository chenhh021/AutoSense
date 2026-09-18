package com.chh.autosense.service.knowledge;

import com.chh.autosense.ai.model.*;
import com.chh.autosense.ai.model.enums.KnowledgeAnswerStatus;
import com.chh.autosense.ai.rag.KnowledgeEmbeddingStore.Catalog;
import com.chh.autosense.ai.rag.KnowledgeQueryRouter;
import com.chh.autosense.config.KnowledgeProperties;
import com.chh.autosense.core.security.AuthUser;
import com.chh.autosense.core.session.memory.ConversationHistorySnapshot;
import com.chh.autosense.domain.dto.*;
import com.chh.autosense.domain.enums.KnowledgeDirectAnswerReason;
import com.chh.autosense.exception.KnowledgeInsufficientException;
import com.chh.autosense.graph.node.AttemptCalls;
import com.chh.autosense.utils.*;
import dev.langchain4j.rag.content.*;
import dev.langchain4j.service.*;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.TimeoutException;

/** Knowledge business only: cached dedicated services, validated sources and direct fallback. */
@Service
public class KnowledgeWorkflowService {
    private final UserAiServiceCache cache;
    private final PromptInputEncoder encoder;
    private final Catalog catalog;
    private final KnowledgeProperties properties;
    private final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
    public KnowledgeWorkflowService(UserAiServiceCache cache, PromptInputEncoder encoder, Catalog catalog, KnowledgeProperties properties) {
        this.cache = cache; this.encoder = encoder; this.catalog = catalog; this.properties = properties;
    }
    public record Request(AuthUser user, String content, ConversationHistorySnapshot history, boolean requiresKnowledgeBase, Instant deadline) { }
    public record Answer(Map<String, Object> data, TokenStream stream, String prefix) { }
    public Answer answer(Request request) throws Exception {
        check(request);
        if (!request.requiresKnowledgeBase()) return direct(request, KnowledgeDirectAnswerReason.COMMON_SENSE);
        var services = cache.getOrCreate(request.user());
        var analysis = analysis(request, services);
        String type = catalog.normalizeType(analysis.deviceType());
        if (type == null) return direct(request, KnowledgeDirectAnswerReason.TYPE_UNKNOWN);
        var scope = new KnowledgeAnswerRequest.Scope(type, analysis.brand(), analysis.model(), analysis.missingInformation(), analysis.version(), analysis.environment());
        var envelope = new KnowledgeAnswerRequest(encoder.visibleHistory(request.history()), request.content(), analysis.queryText(), scope, request.deadline().toEpochMilli());
        var call = AiCallLog.start("enhancedAnswer");
        try {
            String input = encoder.knowledgeRequest(envelope); check(request);
            call.phase(AiCallLog.Phase.MODEL_INVOCATION);
            Result<KnowledgeAnswer> result = services.enhanced().answerKnowledge(input);
            call.phase(AiCallLog.Phase.OUTPUT_VALIDATION); check(request);
            String display = display(result, scope);
            var cited = new HashSet<>(result.content().sourceIds());
            var sources = result.sources().stream().filter(c -> cited.contains(c.textSegment().metadata().getString("sourceId"))).map(c -> {
                var metadata = c.textSegment().metadata();
                return Map.of("sourceId", metadata.getString("sourceId"), "sourceName", metadata.getString("sourceName"),
                        "documentHash", metadata.getString("documentHash"), "model", metadata.getString("productKey"));
            }).distinct().toList();
            call.completed(); return new Answer(Map.of("answer", display, "sources", sources), null, "");
        } catch (KnowledgeInsufficientException insufficient) {
            call.skipped(insufficient.reason().name()); return direct(request, insufficient.reason());
        } catch (Exception e) { call.failed(e); throw e; }
    }
    private KnowledgeQueryAnalysis analysis(Request request, UserAiServiceCache.UserAiServices services) throws Exception {
        AttemptCalls attempt;
        try { attempt = AttemptCalls.current(); } catch (IllegalStateException e) { attempt = null; }
        if (attempt != null) {
            String value = (String) attempt.call("knowledge-analysis", remaining -> json.writeValueAsString(analyze(request, services)));
            return json.readValue(value, KnowledgeQueryAnalysis.class);
        }
        return analyze(request, services);
    }
    private KnowledgeQueryAnalysis analyze(Request request, UserAiServiceCache.UserAiServices services) throws Exception {
        var call = AiCallLog.start("queryAnalysis");
        try {
            String history = encoder.history(request.history()), text = encoder.text(request.content()), types = encoder.knowledgeCatalog(catalog);
            check(request); call.phase(AiCallLog.Phase.MODEL_INVOCATION);
            var analysis = services.analysis().analyzeKnowledge(history, text, types);
            call.phase(AiCallLog.Phase.OUTPUT_VALIDATION);
            if (analysis == null || analysis.queryText() == null || analysis.queryText().isBlank()) throw new IllegalStateException("Invalid knowledge analysis");
            check(request); call.completed(); return analysis;
        } catch (Exception e) { call.failed(e); throw e; }
    }
    private Answer direct(Request request, KnowledgeDirectAnswerReason reason) throws TimeoutException {
        check(request);
        String prefix = switch (reason) {
            case COMMON_SENSE -> "";
            case TYPE_UNKNOWN -> "无法确定设备类型，缺少可靠的专门资料，以下基于一般知识回答。\n\n";
            case NO_MATCH, LOW_RELEVANCE -> "未检索到足够相关的资料，以下基于一般知识回答，具体参数请核对对应型号说明书。\n\n";
        };
        var stream = cache.getOrCreate(request.user()).direct().answerKnowledge(encoder.history(request.history()), encoder.text(request.content()),
                encoder.answerContext(new KnowledgeDirectAnswerContext(reason)));
        return new Answer(Map.of(), stream, prefix);
    }
    private static void check(Request request) throws TimeoutException {
        if (Thread.currentThread().isInterrupted() || !Instant.now().isBefore(request.deadline())) throw new TimeoutException("Knowledge attempt deadline exceeded");
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
}
