package com.chh.autosense.support;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Countable vectors with a known relevance boundary; no network or production model. */
public final class DeterministicEmbeddingModel implements EmbeddingModel {
    public final AtomicInteger calls = new AtomicInteger();
    public final AtomicInteger indexingCalls = new AtomicInteger();
    public final AtomicInteger queryCalls = new AtomicInteger();
    public final java.util.List<String> queryTexts = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
        calls.incrementAndGet();
        if (segments.stream().allMatch(s -> s.metadata().containsKey("sourceId"))) indexingCalls.incrementAndGet();
        else {
            queryCalls.incrementAndGet();
            segments.forEach(s -> queryTexts.add(s.text()));
        }
        return Response.from(segments.stream().map(segment -> vector(segment.text())).toList());
    }

    public static Embedding vector(String text) {
        if (text.contains("boundary")) return Embedding.from(new float[]{0.5f, (float) Math.sqrt(0.75), 0});
        if (text.contains("irrelevant")) return Embedding.from(new float[]{-1, 0, 0});
        if (text.contains("air")) return Embedding.from(new float[]{0, 1, 0});
        return Embedding.from(new float[]{1, 0, 0});
    }

    @Override
    public int dimension() { return 3; }
}
