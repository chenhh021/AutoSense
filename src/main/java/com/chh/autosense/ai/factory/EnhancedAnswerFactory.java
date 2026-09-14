package com.chh.autosense.ai.factory;

import com.chh.autosense.ai.EnhancedAnswerService;
import com.chh.autosense.ai.rag.KnowledgeEmbeddingStore.Catalog;
import com.chh.autosense.ai.rag.KnowledgeQueryRouter;
import com.chh.autosense.config.KnowledgeProperties;
import com.chh.autosense.utils.AiServiceValidator;
import com.chh.autosense.utils.PromptInputEncoder;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.EmbeddingStore;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import java.util.List;
import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/** Builds fresh analysis or enhanced proxies; all per-request context stays inside the SDK invocation. */
@Component
public class EnhancedAnswerFactory {
    private final ChatModel chatModel;
    private final EmbeddingStore<TextSegment> store;
    private final EmbeddingModel embeddingModel;
    private final KnowledgeProperties properties;
    private final Catalog catalog;
    private final PromptInputEncoder encoder;

    public EnhancedAnswerFactory(ChatModel chatModel, EmbeddingStore<TextSegment> store, EmbeddingModel embeddingModel,
                                  KnowledgeProperties properties, Catalog catalog, PromptInputEncoder encoder) {
        this.chatModel = chatModel;
        this.store = store;
        this.embeddingModel = embeddingModel;
        this.properties = properties;
        this.catalog = catalog;
        this.encoder = encoder;
    }

    @PostConstruct
    public void validate() { AiServiceValidator.validateServices(List.of(EnhancedAnswerService.class)); }

    /** Both analyze and analyzeKnowledge must use this unaugmented proxy. */
    public EnhancedAnswerService problemAnalysisService() {
        validate();
        return AiServices.builder(EnhancedAnswerService.class).chatModel(chatModel).build();
    }

    public EnhancedAnswerService enhancedAnswerService() {
        validate();
        var retriever = EmbeddingStoreContentRetriever.builder().embeddingStore(store).embeddingModel(embeddingModel)
                .maxResults(properties.retrieval().topK()).minScore(0.0)
                .dynamicFilter(query -> {
                    var message = (UserMessage) query.metadata().chatMessage();
                    var request = encoder.readKnowledgeRequest(message.singleText());
                    String type = catalog.normalizeType(request.scope().deviceType());
                    if (type == null) throw new IllegalArgumentException("Knowledge device type is invalid");
                    KnowledgeQueryRouter.checkDeadline(request);
                    return metadataKey("deviceType").isEqualTo(type);
                }).build();
        var router = new KnowledgeQueryRouter(retriever, properties.retrieval().minScore(), catalog, encoder);
        var augmentor = DefaultRetrievalAugmentor.builder()
                .queryTransformer(query -> {
                    var request = router.request(query);
                    return List.of(Query.from(request.queryText(), query.metadata()));
                })
                .queryRouter(router)
                .contentInjector((contents, message) -> {
                    var request = router.validate(encoder.readKnowledgeRequest(((UserMessage) message).singleText()));
                    return UserMessage.from(encoder.knowledgeEvidence(request, contents));
                }).build();
        return AiServices.builder(EnhancedAnswerService.class).chatModel(chatModel).retrievalAugmentor(augmentor).build();
    }
}
