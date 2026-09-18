package com.chh.autosense.config;

import com.chh.autosense.utils.AiCallLog;
import com.chh.autosense.utils.LogSanitizer;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration(proxyBeanMethods = false)
@lombok.extern.slf4j.Slf4j
public class KnowledgeEmbeddingConfig {
    @Bean
    public EmbeddingModel knowledgeEmbeddingModel(KnowledgeEmbeddingProperties properties,
                                                   LlmProperties llm, GraphProperties graph) {
        properties.validate(llm, graph);
        EmbeddingModel delegate;
        if ("mock".equals(properties.provider())) {
            delegate = new LocalEmbeddingModel(properties.dimensions() == null ? 64 : properties.dimensions());
        } else {
            delegate = new EmbeddingModel() {
                @Override public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
                    var builder = OpenAiEmbeddingModel.builder().baseUrl(properties.baseUrl()).apiKey(properties.apiKey())
                            .modelName(properties.modelName()).timeout(com.chh.autosense.graph.node.AttemptCalls.limit(Duration.ofSeconds(properties.timeoutSeconds())))
                            .maxRetries(0).maxSegmentsPerBatch(properties.maxSegmentsPerBatch()).logRequests(false).logResponses(false);
                    if (properties.dimensions() != null) builder.dimensions(properties.dimensions());
                    return builder.build().embedAll(segments);
                }
            };
        }
        log.info("Knowledge embedding configured: provider={}, model={}, dimensions={}, timeoutSeconds={}, maxRetries={}",
                properties.provider(), LogSanitizer.label(properties.modelName()), properties.dimensions(),
                properties.timeoutSeconds(), properties.maxRetries());
        return validated(delegate, properties.dimensions());
    }

    /** Same vector-space validation for ingestion and queries; never probes the provider on construction. */
    public static EmbeddingModel validated(EmbeddingModel delegate, Integer expectedDimensions) {
        return new ValidatedEmbeddingModel(delegate, expectedDimensions);
    }

    private static final class ValidatedEmbeddingModel implements EmbeddingModel {
        private final EmbeddingModel delegate;
        private final AtomicInteger dimensions;

        private ValidatedEmbeddingModel(EmbeddingModel delegate, Integer expected) {
            this.delegate = Objects.requireNonNull(delegate);
            this.dimensions = new AtomicInteger(expected == null ? 0 : expected);
        }

        @Override public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            boolean indexing = segments.stream().allMatch(s -> s.metadata().containsKey("sourceId"));
            var call = AiCallLog.start(indexing ? "indexEmbedding" : "queryEmbedding");
            try {
                call.phase(AiCallLog.Phase.MODEL_INVOCATION);
                var response = delegate.embedAll(segments);
                call.phase(AiCallLog.Phase.OUTPUT_VALIDATION);
                if (response == null || response.content() == null || response.content().size() != segments.size())
                    throw new IllegalStateException("Embedding vector count mismatch");
                for (var vector : response.content()) {
                    if (vector == null || vector.vector().length == 0) throw new IllegalStateException("Embedding vector is empty");
                    int size = vector.vector().length;
                    dimensions.compareAndSet(0, size);
                    if (dimensions.get() != size) throw new IllegalStateException("Embedding vector dimension mismatch");
                    for (float value : vector.vector()) if (!Float.isFinite(value))
                        throw new IllegalStateException("Embedding vector contains nonfinite values");
                }
                call.completed();
                return response;
            } catch (RuntimeException e) {
                call.failed(e);
                throw e;
            }
        }

        @Override public int dimension() {
            if (dimensions.get() == 0) throw new IllegalStateException("Embedding dimension is not initialized");
            return dimensions.get();
        }
    }

    /** Explicit local mode only. Deterministic character hashing is not a production semantic model. */
    private record LocalEmbeddingModel(int dimension) implements EmbeddingModel {
        @Override public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            return Response.from(segments.stream().map(segment -> {
                float[] vector = new float[dimension];
                segment.text().toLowerCase(java.util.Locale.ROOT).codePoints()
                        .forEach(value -> vector[Math.floorMod(value, dimension)] += 1);
                if (segment.text().isEmpty()) vector[0] = 1;
                return Embedding.from(vector);
            }).toList());
        }
    }
}
