package com.chh.autosense.ai.rag;

import com.chh.autosense.domain.dto.KnowledgeAnswerRequest;
import com.chh.autosense.domain.enums.KnowledgeDirectAnswerReason;
import com.chh.autosense.exception.ApiException;
import com.chh.autosense.exception.ErrorCode;
import com.chh.autosense.exception.KnowledgeInsufficientException;
import com.chh.autosense.utils.PromptInputEncoder;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.router.QueryRouter;
import lombok.extern.slf4j.Slf4j;
import java.util.*;

/** One real search, then request-local immutable replay through the SDK augmentor. */
@Slf4j
public final class KnowledgeQueryRouter implements QueryRouter {
    private final ContentRetriever retriever;
    private final double minScore;
    private final KnowledgeEmbeddingStore.Catalog catalog;
    private final PromptInputEncoder encoder;

    public KnowledgeQueryRouter(ContentRetriever retriever, double minScore,
                                 KnowledgeEmbeddingStore.Catalog catalog, PromptInputEncoder encoder) {
        this.retriever = Objects.requireNonNull(retriever);
        if (!Double.isFinite(minScore) || minScore < 0 || minScore > 1) throw new IllegalArgumentException("Invalid score threshold");
        this.minScore = minScore;
        this.catalog = Objects.requireNonNull(catalog);
        this.encoder = Objects.requireNonNull(encoder);
    }

    public KnowledgeAnswerRequest request(Query query) {
        if (query.metadata() == null || !(query.metadata().chatMessage() instanceof UserMessage message))
            throw new IllegalArgumentException("Knowledge query metadata is missing");
        return validate(encoder.readKnowledgeRequest(message.singleText()));
    }

    public KnowledgeAnswerRequest validate(KnowledgeAnswerRequest request) {
        if (catalog.normalizeType(request.scope().deviceType()) == null)
            throw new IllegalArgumentException("Knowledge device type is invalid");
        checkDeadline(request);
        return request;
    }

    public static void checkDeadline(KnowledgeAnswerRequest request) {
        if (Thread.currentThread().isInterrupted() || request.deadlineEpochMillis() <= System.currentTimeMillis()) {
            var failure = new ApiException(ErrorCode.REQUEST_TIMEOUT, "知识咨询处理超时，请重试。");
            failure.initCause(new java.util.concurrent.TimeoutException("Knowledge retrieval deadline exceeded"));
            throw failure;
        }
    }

    @Override public Collection<ContentRetriever> route(Query query) {
        KnowledgeAnswerRequest request = request(query);
        String type = catalog.normalizeType(request.scope().deviceType());
        long started = System.nanoTime();
        List<Content> candidates;
        try {
            candidates = retrieve(query);
            checkDeadline(request);
            for (Content content : candidates) validateContent(content, type);
        } catch (RuntimeException e) {
            log.warn("Knowledge retrieval failed: operation=retrieve, errorType={}, elapsedMs={}",
                    e.getClass().getSimpleName(), (System.nanoTime() - started) / 1_000_000);
            throw e;
        }
        if (candidates.isEmpty()) throw insufficient(KnowledgeDirectAnswerReason.NO_MATCH);
        List<Content> hits = List.copyOf(candidates.stream()
                .filter(content -> ((Number) content.metadata().get(ContentMetadata.SCORE)).doubleValue() >= minScore)
                .toList());
        if (hits.isEmpty()) throw insufficient(KnowledgeDirectAnswerReason.LOW_RELEVANCE);
        log.info("Knowledge retrieval completed: operation=retrieve, candidates={}, accepted={}, elapsedMs={}",
                candidates.size(), hits.size(), (System.nanoTime() - started) / 1_000_000);
        return List.of(ignored -> hits);
    }

    @SuppressWarnings("unchecked")
    private List<Content> retrieve(Query query) {
        com.chh.autosense.graph.node.AttemptCalls attempt;
        try { attempt = com.chh.autosense.graph.node.AttemptCalls.current(); }
        catch (IllegalStateException e) { return Objects.requireNonNull(retriever.retrieve(query)); }
        try {
            var cached = (List<Map<String, Object>>) attempt.call("knowledge-retrieval", remaining ->
                    Objects.requireNonNull(retriever.retrieve(query)).stream().map(content -> Map.of(
                            "text", content.textSegment().text(), "metadata", content.textSegment().metadata().toMap(),
                            "score", content.metadata().get(ContentMetadata.SCORE))).toList());
            return cached.stream().map(row -> Content.from(dev.langchain4j.data.segment.TextSegment.from((String) row.get("text"),
                    new dev.langchain4j.data.document.Metadata((Map<String, Object>) row.get("metadata"))),
                    Map.of(ContentMetadata.SCORE, row.get("score")))).toList();
        } catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new java.util.concurrent.CompletionException(e); }
    }

    private static KnowledgeInsufficientException insufficient(KnowledgeDirectAnswerReason reason) {
        log.info("Knowledge retrieval insufficient: operation=retrieve, reason={}", reason);
        return new KnowledgeInsufficientException(reason);
    }

    /** Reused at the publication boundary to validate Result.sources independently of model output. */
    public static void validateContent(Content content, String type) {
        if (content == null || content.textSegment() == null || content.textSegment().text().isBlank()
                || !(content.metadata().get(ContentMetadata.SCORE) instanceof Number score)
                || !Double.isFinite(score.doubleValue()) || score.doubleValue() < 0 || score.doubleValue() > 1)
            throw new IllegalStateException("Invalid knowledge retrieval score or content");
        var metadata = content.textSegment().metadata();
        for (String key : List.of("sourceId", "sourceName", "documentHash", "index", "deviceType", "brand", "model",
                "productKey", "knowledgeKind")) {
            if (!metadata.containsKey(key) || metadata.getString(key) == null || metadata.getString(key).isBlank())
                throw new IllegalStateException("Incomplete knowledge source metadata");
        }
        String kind = metadata.getString("knowledgeKind");
        String file = switch (kind) {
            case "GENERAL" -> "general.md";
            case "TROUBLESHOOTING" -> "troubleshot.md";
            default -> throw new IllegalStateException("Invalid knowledge kind");
        };
        String product = metadata.getString("brand") + "-" + metadata.getString("model");
        if (!type.equals(metadata.getString("deviceType"))
                || !metadata.getString("brand").matches("[A-Za-z0-9_]+")
                || !metadata.getString("model").matches("[A-Za-z0-9][A-Za-z0-9._-]*")
                || !product.equals(metadata.getString("productKey"))
                || !("document/" + type + "/" + product + "/" + file).equals(metadata.getString("sourceId"))
                || !metadata.getString("documentHash").matches("[0-9a-f]{64}")
                || !metadata.getString("index").matches("[0-9]+"))
            throw new IllegalStateException("Invalid knowledge source metadata");
    }
}
