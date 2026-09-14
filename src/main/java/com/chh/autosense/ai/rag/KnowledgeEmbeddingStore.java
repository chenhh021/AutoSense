package com.chh.autosense.ai.rag;

import com.chh.autosense.config.KnowledgeProperties;
import com.chh.autosense.exception.KnowledgeInitializationException;
import dev.langchain4j.data.document.*;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** One synchronous startup import. No runtime reload API or independently maintained index snapshot. */
@Slf4j
@Configuration(value = "knowledgeStoreConfiguration", proxyBeanMethods = false)
public class KnowledgeEmbeddingStore {
    private static final Pattern PATH = Pattern.compile(
            "document/([a-z][a-z0-9_]*)/([A-Za-z0-9_]+)-([A-Za-z0-9][A-Za-z0-9._-]*)/(general|troubleshot)\\.md");
    private final KnowledgeProperties properties;
    private final EmbeddingModel model;
    private final ResourcePatternResolver resolver;
    private final DocumentParser parser;
    private final Supplier<EmbeddingStore<TextSegment>> stores;
    // Published only on the container thread after the entire startup job has succeeded.
    private Catalog catalog;

    @Autowired
    public KnowledgeEmbeddingStore(KnowledgeProperties properties, EmbeddingModel model, ApplicationContext context) {
        this(properties, model, new PathMatchingResourcePatternResolver(context));
    }

    public KnowledgeEmbeddingStore(KnowledgeProperties properties, EmbeddingModel model, ResourcePatternResolver resolver) {
        this(properties, model, resolver, new TextDocumentParser(StandardCharsets.UTF_8), InMemoryEmbeddingStore::new);
    }

    public KnowledgeEmbeddingStore(KnowledgeProperties properties, EmbeddingModel model, ResourcePatternResolver resolver,
                                    DocumentParser parser, Supplier<EmbeddingStore<TextSegment>> stores) {
        this.properties = properties;
        this.model = model;
        this.resolver = resolver;
        this.parser = parser;
        this.stores = stores;
    }

    @Bean
    @Lazy(false)
    public EmbeddingStore<TextSegment> knowledgeEmbeddingStore() {
        long started = System.nanoTime();
        var executor = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("knowledge-startup").factory());
        Future<Loaded> task = executor.submit(this::load);
        try {
            Loaded loaded = task.get(properties.documents().startupTimeoutSeconds(), TimeUnit.SECONDS);
            catalog = loaded.catalog();
            log.info("Knowledge index loaded: operation=indexLoad, documents={}, segments={}, elapsedMs={}",
                    loaded.documents(), loaded.segments(), (System.nanoTime() - started) / 1_000_000);
            return loaded.store();
        } catch (TimeoutException e) {
            task.cancel(true);
            throw failure("INITIALIZATION", "TIMEOUT", null);
        } catch (InterruptedException e) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            throw failure("INITIALIZATION", "INTERRUPTED", null);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof KnowledgeInitializationException safe) throw safe;
            throw failure("INITIALIZATION", "FAILED", null);
        } finally {
            // Do not wait indefinitely for a provider that ignores cancellation. It cannot publish a Bean.
            executor.shutdownNow();
        }
    }

    @Bean
    public Catalog knowledgeCatalog(EmbeddingStore<TextSegment> knowledgeEmbeddingStore) {
        if (catalog == null) throw new IllegalStateException("Knowledge catalog is not initialized");
        return catalog;
    }

    private Loaded load() {
        String stage = "DISCOVERING";
        String sourceId = null;
        try {
            Resource[] resources = resolver.getResources(properties.documents().location());
            if (resources.length == 0) throw failure(stage, "MISSING_DOCUMENTS", null);
            var paths = new TreeMap<String, Resource>();
            for (Resource resource : resources) {
                String url = resource.getURL().toExternalForm();
                int root = url.lastIndexOf("/document/");
                String path = root < 0 ? "" : url.substring(root + 1);
                if (!PATH.matcher(path).matches() || path.contains("..") || path.length() > 300)
                    throw failure("VALIDATING", "INVALID_PATH", null);
                if (paths.putIfAbsent(path, resource) != null) throw failure("VALIDATING", "DUPLICATE_SOURCE", path);
            }
            var products = new TreeMap<String, Set<String>>();
            var files = new HashMap<String, Set<String>>();
            for (String path : paths.keySet()) {
                var match = PATH.matcher(path);
                if (!match.matches()) throw failure("VALIDATING", "INVALID_PATH", null);
                String product = match.group(2) + "-" + match.group(3);
                products.computeIfAbsent(match.group(1), ignored -> new TreeSet<>()).add(product);
                files.computeIfAbsent(match.group(1) + "/" + product, ignored -> new HashSet<>()).add(match.group(4));
            }
            if (files.values().stream().anyMatch(names -> !names.equals(Set.of("general", "troubleshot"))))
                throw failure("VALIDATING", "MISSING_PAIRED_DOCUMENT", null);
            List<Document> documents = new ArrayList<>();
            long bytesRead = 0;
            for (var entry : paths.entrySet()) {
                checkInterrupted();
                sourceId = entry.getKey();
                stage = "VALIDATING";
                byte[] bytes;
                try (InputStream input = entry.getValue().getInputStream()) {
                    bytes = input.readNBytes((int) (properties.documents().maxTotalBytes() - bytesRead + 1));
                }
                bytesRead += bytes.length;
                if (bytesRead > properties.documents().maxTotalBytes()) throw failure(stage, "BYTE_LIMIT", sourceId);
                if (bytes.length >= 3 && bytes[0] == (byte) 0xef && bytes[1] == (byte) 0xbb && bytes[2] == (byte) 0xbf)
                    throw failure(stage, "BOM_NOT_ALLOWED", sourceId);
                String decoded = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
                if (decoded.isBlank()) throw failure(stage, "EMPTY_DOCUMENT", sourceId);
                var match = PATH.matcher(sourceId);
                if (!match.matches()) throw failure(stage, "INVALID_PATH", null);
                String product = match.group(2) + "-" + match.group(3);
                String kind = match.group(4).equals("general") ? "GENERAL" : "TROUBLESHOOTING";
                Metadata metadata = Metadata.from(Map.of("deviceType", match.group(1), "brand", match.group(2),
                        "model", match.group(3), "productKey", product, "knowledgeKind", kind,
                        "sourceId", sourceId, "sourceName", product + "/" + match.group(4) + ".md",
                        "documentHash", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))));
                stage = "PARSING";
                Document document = DocumentLoader.load(new DocumentSource() {
                    @Override public InputStream inputStream() { return new ByteArrayInputStream(bytes); }
                    @Override public Metadata metadata() { return metadata; }
                }, parser);
                if (document.text().isBlank()) throw failure(stage, "EMPTY_DOCUMENT", sourceId);
                documents.add(document);
            }
            sourceId = null;
            AtomicInteger segmentCount = new AtomicInteger();
            var sdkSplitter = DocumentSplitters.recursive(properties.documents().maxSegmentChars(),
                    properties.documents().overlapChars());
            DocumentSplitter splitter = document -> {
                checkInterrupted();
                var segments = sdkSplitter.split(document);
                if (segmentCount.addAndGet(segments.size()) > properties.documents().maxSegments())
                    throw failure("SPLITTING", "SEGMENT_LIMIT", document.metadata().getString("sourceId"));
                return segments;
            };
            EmbeddingStore<TextSegment> store = stores.get();
            // SDK owns splitting and ingestion; tiny adapters only add validation/cancellation boundaries.
            EmbeddingModel guarded = segments -> {
                checkInterrupted();
                try {
                    return model.embedAll(segments);
                }
                catch (RuntimeException e) { throw failure("EMBEDDING", "MODEL_OR_VECTOR_FAILED", null); }
            };
            EmbeddingStore<TextSegment> writeTarget = new EmbeddingStore<>() {
                @Override public dev.langchain4j.store.embedding.EmbeddingSearchResult<TextSegment> search(
                        dev.langchain4j.store.embedding.EmbeddingSearchRequest request) { throw new UnsupportedOperationException(); }
                @Override public String add(dev.langchain4j.data.embedding.Embedding embedding) { throw new UnsupportedOperationException(); }
                @Override public void add(String id, dev.langchain4j.data.embedding.Embedding embedding) { throw new UnsupportedOperationException(); }
                @Override public String add(dev.langchain4j.data.embedding.Embedding embedding, TextSegment segment) { throw new UnsupportedOperationException(); }
                @Override public List<String> addAll(List<dev.langchain4j.data.embedding.Embedding> embeddings) { throw new UnsupportedOperationException(); }
                @Override public List<String> addAll(List<dev.langchain4j.data.embedding.Embedding> embeddings, List<TextSegment> segments) {
                    checkInterrupted();
                    try {
                        var ids = store.addAll(embeddings, segments);
                        if (ids == null || ids.size() != segments.size()) throw new IllegalStateException("Write count mismatch");
                        return ids;
                    } catch (RuntimeException e) { throw failure("WRITING", "STORE_WRITE_FAILED", null); }
                }
            };
            stage = "INGESTING";
            EmbeddingStoreIngestor.builder().documentSplitter(splitter).embeddingModel(guarded)
                    .embeddingStore(writeTarget).build().ingest(documents);
            checkInterrupted();
            var names = new TreeMap<String, List<String>>();
            products.forEach((type, values) -> names.put(type, List.copyOf(values)));
            return new Loaded(store, new Catalog(names, properties.typeAliases()), documents.size(), segmentCount.get());
        } catch (KnowledgeInitializationException e) {
            throw e;
        } catch (Exception e) {
            throw failure(stage, "INVALID_RESOURCE_OR_IMPORT", sourceId);
        }
    }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) throw failure("INITIALIZATION", "INTERRUPTED", null);
    }

    private static KnowledgeInitializationException failure(String stage, String reason, String source) {
        var failure = new KnowledgeInitializationException(stage, reason, source);
        log.error("Knowledge index initialization failed: operation=indexLoad, diagnostic={}", failure.getMessage());
        return failure;
    }

    private record Loaded(EmbeddingStore<TextSegment> store, Catalog catalog, int documents, int segments) { }

    /** Names only, no duplicate document text; configured known types may have no indexed product. */
    public record Catalog(Map<String, List<String>> productsByType, Map<String, String> aliases) {
        public Catalog {
            var copy = new TreeMap<String, List<String>>();
            productsByType.forEach((type, products) -> copy.put(type, List.copyOf(products)));
            productsByType = Map.copyOf(copy);
            aliases = Map.copyOf(aliases);
            for (var alias : aliases.entrySet()) {
                if (productsByType.containsKey(alias.getKey()) && !alias.getKey().equals(alias.getValue()))
                    throw new IllegalArgumentException("Ambiguous canonical knowledge type alias");
            }
        }

        public String normalizeType(String value) {
            if (value == null) return null;
            String key = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
            if (productsByType.containsKey(key) || aliases.containsValue(key)) return key;
            return aliases.get(key);
        }
    }
}
