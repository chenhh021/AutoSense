package com.chh.autosense.unit;

import com.chh.autosense.ai.rag.KnowledgeEmbeddingStore;
import com.chh.autosense.config.KnowledgeEmbeddingConfig;
import com.chh.autosense.exception.KnowledgeInitializationException;
import com.chh.autosense.support.DeterministicEmbeddingModel;
import com.chh.autosense.support.KnowledgeFixtures;
import dev.langchain4j.data.document.DocumentParser;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class KnowledgeIndexInitializationTest {
    private KnowledgeEmbeddingStore initializer(Map<String, String> settings, EmbeddingModel delegate,
                                                  Resource... resources) throws Exception {
        return new KnowledgeEmbeddingStore(KnowledgeConfigurationTest.properties(settings),
                KnowledgeEmbeddingConfig.validated(delegate, null), KnowledgeFixtures.resolver(resources));
    }

    private List<TextSegment> segments(EmbeddingStore<TextSegment> store) {
        return store.search(EmbeddingSearchRequest.builder().queryEmbedding(DeterministicEmbeddingModel.vector("light"))
                .maxResults(100).minScore(0.0).build()).matches().stream().map(m -> m.embedded()).toList();
    }

    @Test void importsCompleteMetadataAndRebuildsStableSourcesWithoutDatabase() throws Exception {
        var model = new DeterministicEmbeddingModel();
        var first = initializer(Map.of(), model, KnowledgeFixtures.multipleProducts()).knowledgeEmbeddingStore();
        var second = initializer(Map.of(), model, KnowledgeFixtures.multipleProducts()).knowledgeEmbeddingStore();
        assertThat(first).isInstanceOf(InMemoryEmbeddingStore.class).isNotSameAs(second);
        assertThat(segments(first)).containsExactlyInAnyOrderElementsOf(segments(second)).hasSize(6);
        assertThat(segments(first)).allSatisfy(segment -> {
            var metadata = segment.metadata();
            assertThat(metadata.getString("deviceType")).isIn("light", "air");
            assertThat(metadata.getString("model")).isNotBlank();
            assertThat(metadata.getString("knowledgeKind")).isIn("GENERAL", "TROUBLESHOOTING");
            assertThat(metadata.getString("index")).isEqualTo("0");
            assertThat(metadata.containsKey("segmentId")).isFalse();
            assertThat(metadata.getString("documentHash")).hasSize(64);
        });
        assertThat(model.indexingCalls).hasValue(2);
        assertThat(model.queryCalls).hasValue(0);
    }

    @Test void missingMalformedDuplicateAndOversizeResourcesFailBeforeEmbedding() throws Exception {
        var good = KnowledgeFixtures.light();
        var bad = List.of(new Resource[0], new Resource[]{good[0]}, new Resource[]{good[0], good[1], good[0]},
                KnowledgeFixtures.product("light", "MI-A", "  ", "ok"),
                new Resource[]{KnowledgeFixtures.resource("document/light/MI-A/general.md", new byte[]{(byte) 0xc3, 0x28}),
                        KnowledgeFixtures.resource("document/light/MI-A/troubleshot.md", "ok")},
                KnowledgeFixtures.product("light", "MI-A", "\ufeffbad", "ok"),
                KnowledgeFixtures.product("../light", "MI-A", "bad", "ok"),
                new Resource[]{good[0], good[1], KnowledgeFixtures.resource("document/light/MI-A/unknown.md", "bad")});
        for (Resource[] resources : bad) {
            var model = new DeterministicEmbeddingModel();
            assertThatThrownBy(initializer(Map.of(), model, resources)::knowledgeEmbeddingStore)
                    .isInstanceOf(KnowledgeInitializationException.class).hasNoCause();
            assertThat(model.calls).hasValue(0);
        }
        for (var limit : Map.of("max-total-bytes", "1", "max-segments", "1").entrySet()) {
            var model = new DeterministicEmbeddingModel();
            assertThatThrownBy(() -> initializer(Map.of("autosense.knowledge.documents." + limit.getKey(), limit.getValue()),
                    model, good).knowledgeEmbeddingStore()).isInstanceOf(KnowledgeInitializationException.class);
            assertThat(model.calls).hasValue(0);
        }
    }

    @Test void invalidVectorsAndWriteFailureCannotReturnAnIndex() throws Exception {
        for (EmbeddingModel bad : List.<EmbeddingModel>of(
                segments -> { throw new IllegalStateException("SECRET-BODY"); },
                segments -> Response.from(List.of()),
                segments -> Response.from(List.of(Embedding.from(new float[]{1, 0}), Embedding.from(new float[]{1}))),
                segments -> Response.from(segments.stream().map(s -> Embedding.from(new float[]{Float.NaN})).toList()))) {
            assertThatThrownBy(() -> initializer(Map.of(), bad, KnowledgeFixtures.light()).knowledgeEmbeddingStore())
                    .isInstanceOf(KnowledgeInitializationException.class).hasMessageNotContaining("SECRET-BODY").hasNoCause();
        }
        @SuppressWarnings("unchecked") var failedStore = (EmbeddingStore<TextSegment>) mock(EmbeddingStore.class);
        when(failedStore.addAll(anyList(), anyList())).thenThrow(new IllegalStateException("SECRET-DOCUMENT"));
        var init = new KnowledgeEmbeddingStore(KnowledgeConfigurationTest.properties(Map.of()),
                new DeterministicEmbeddingModel(), KnowledgeFixtures.resolver(KnowledgeFixtures.light()),
                new TextDocumentParser(StandardCharsets.UTF_8), () -> failedStore);
        assertThatThrownBy(init::knowledgeEmbeddingStore).isInstanceOf(KnowledgeInitializationException.class)
                .hasMessageContaining("WRITING").hasMessageNotContaining("SECRET-DOCUMENT").hasNoCause();
    }

    @Test void timeoutDoesNotWaitForUncooperativeModelOrPublishLateCatalog() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        EmbeddingModel slow = segments -> {
            started.countDown();
            boolean released = false;
            while (!released) {
                try { released = release.await(2, TimeUnit.SECONDS); }
                catch (InterruptedException ignored) { /* Simulate a provider ignoring cancellation. */ }
            }
            finished.countDown();
            return Response.from(segments.stream().map(s -> Embedding.from(new float[]{1, 0})).toList());
        };
        var init = initializer(Map.of("autosense.knowledge.documents.startup-timeout-seconds", "1"), slow, KnowledgeFixtures.light());
        try {
            assertThatThrownBy(init::knowledgeEmbeddingStore).isInstanceOf(KnowledgeInitializationException.class)
                    .hasMessageContaining("TIMEOUT");
            assertThat(started.getCount()).isZero();
        } finally { release.countDown(); }
        assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> init.knowledgeCatalog(new InMemoryEmbeddingStore<>()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void longMarkdownKeepsMetadataAndParserFailureIsSanitized() throws Exception {
        String markdown = "# 灯泡说明\n\n" + "中文长段落。".repeat(400);
        var store = initializer(Map.of(), new DeterministicEmbeddingModel(),
                KnowledgeFixtures.product("light", "MI-LONG", markdown, "排故说明")).knowledgeEmbeddingStore();
        assertThat(segments(store)).hasSizeGreaterThan(2).allSatisfy(s -> {
            assertThat(s.text().length()).isLessThanOrEqualTo(1000);
            assertThat(s.metadata().getString("index")).isNotBlank();
        });
        DocumentParser bad = input -> { throw new IllegalStateException("SECRET-PARSE-DOCUMENT"); };
        var init = new KnowledgeEmbeddingStore(KnowledgeConfigurationTest.properties(Map.of()),
                new DeterministicEmbeddingModel(), KnowledgeFixtures.resolver(KnowledgeFixtures.light()),
                bad, InMemoryEmbeddingStore::new);
        assertThatThrownBy(init::knowledgeEmbeddingStore).isInstanceOf(KnowledgeInitializationException.class)
                .hasMessageContaining("PARSING").hasMessageNotContaining("SECRET-PARSE-DOCUMENT").hasNoCause();
    }
}
