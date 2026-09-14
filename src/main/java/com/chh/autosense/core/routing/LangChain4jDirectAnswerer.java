package com.chh.autosense.core.routing;

import com.chh.autosense.ai.factory.DirectAnswerServiceFactory;
import com.chh.autosense.core.session.memory.ConversationHistorySnapshot;
import com.chh.autosense.utils.LogContextUtils;
import com.chh.autosense.utils.PromptInputEncoder;
import com.chh.autosense.utils.AiCallLog;
import dev.langchain4j.service.TokenStream;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * Real-mode streaming direct answer: the TokenStream is adapted to a CompletionStage.
 * SDK callbacks run on other threads, so the whitelisted MDC snapshot is installed per
 * callback and restored afterwards. Failures complete exceptionally; no fallback success.
 */
@Component
@ConditionalOnProperty(name = "autosense.llm.mode", havingValue = "real")
public class LangChain4jDirectAnswerer implements DirectAnswerer {

    private final DirectAnswerServiceFactory directAnswerServiceFactory;
    private final PromptInputEncoder encoder;
    private final com.chh.autosense.service.knowledge.UserAiServiceCache cache;

    public LangChain4jDirectAnswerer(DirectAnswerServiceFactory directAnswerServiceFactory,
                                     PromptInputEncoder encoder) {
        this(directAnswerServiceFactory, encoder, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public LangChain4jDirectAnswerer(DirectAnswerServiceFactory directAnswerServiceFactory,
            PromptInputEncoder encoder, com.chh.autosense.service.knowledge.UserAiServiceCache cache) {
        this.directAnswerServiceFactory = directAnswerServiceFactory;
        this.encoder = encoder;
        this.cache = cache;
    }

    @Override
    public CompletionStage<String> answer(String question, ConversationHistorySnapshot history,
                                          Consumer<String> onToken) {
        return stream("direct-answer", () -> directAnswerServiceFactory.directAnswerService()
                .answer(encoder.history(history), encoder.text(question)), () -> { }, onToken, 0);
    }

    @Override
    public CompletionStage<String> answerKnowledge(CapabilityRequest request,
            com.chh.autosense.domain.dto.KnowledgeDirectAnswerContext context, Consumer<String> onToken) {
        return stream("directAnswer", () -> cache.getOrCreate(request.user()).direct().answerKnowledge(
                encoder.history(request.history()), encoder.text(request.content()), encoder.answerContext(context)),
                () -> KnowledgeCapabilityHandler.checkDeadline(request), onToken,
                Math.max(1, java.time.Duration.between(java.time.LocalDateTime.now(), request.deadline()).toMillis()));
    }

    private CompletionStage<String> stream(String operation, java.util.function.Supplier<TokenStream> create,
            Runnable checkDeadline, Consumer<String> onToken, long timeoutMillis) {
        AiCallLog call = AiCallLog.start(operation);
        Map<String, String> context = LogContextUtils.snapshot();
        CompletableFuture<String> result = new CompletableFuture<>();
        if (timeoutMillis > 0) {
            result.orTimeout(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
            result.whenComplete((value, error) -> {
                if (error instanceof java.util.concurrent.TimeoutException) call.failed(error, AiCallLog.Phase.STREAM_RECEIVE);
            });
        }
        StringBuilder full = new StringBuilder();
        TokenStream stream;
        try {
            checkDeadline.run();
            call.phase(AiCallLog.Phase.SERVICE_SETUP);
            stream = create.get();
        } catch (RuntimeException e) {
            call.failed(e);
            result.completeExceptionally(e);
            return result;
        }
        try {
            checkDeadline.run();
            call.phase(AiCallLog.Phase.STREAM_START);
            stream.onPartialResponse(token -> {
                    if (result.isDone()) {
                        return;
                    }
                    try (var ignored = LogContextUtils.install(context)) {
                        checkDeadline.run();
                        call.firstResponse();
                        full.append(token);
                        onToken.accept(token);
                    } catch (RuntimeException e) {
                        if (result.completeExceptionally(e)) call.failed(e, AiCallLog.Phase.TOKEN_CALLBACK);
                    }
                })
                .onCompleteResponse(response -> {
                    try (var ignored = LogContextUtils.install(context)) {
                        if (result.isDone()) return;
                        checkDeadline.run();
                        if (full.isEmpty()) throw new IllegalStateException("Empty streaming answer");
                        if (result.complete(full.toString())) call.completed();
                    } catch (RuntimeException e) {
                        if (result.completeExceptionally(e)) call.failed(e, AiCallLog.Phase.OUTPUT_VALIDATION);
                    }
                })
                .onError(error -> {
                    try (var ignored = LogContextUtils.install(context)) {
                        Throwable failure = error == null ? new IllegalStateException("Streaming answer failed") : error;
                        if (result.completeExceptionally(failure)) call.failed(failure, AiCallLog.Phase.STREAM_RECEIVE);
                    }
                })
                .start();
            if (!result.isDone()) call.phase(AiCallLog.Phase.STREAM_RECEIVE);
        } catch (RuntimeException e) {
            if (result.completeExceptionally(e)) call.failed(e, AiCallLog.Phase.STREAM_START);
        }
        return result;
    }
}
