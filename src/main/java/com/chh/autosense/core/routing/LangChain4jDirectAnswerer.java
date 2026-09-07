package com.chh.autosense.core.routing;

import com.chh.autosense.ai.factory.DirectAnswerServiceFactory;
import com.chh.autosense.core.session.memory.ConversationHistorySnapshot;
import com.chh.autosense.utils.LogContextUtils;
import com.chh.autosense.utils.PromptInputEncoder;
import dev.langchain4j.service.TokenStream;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
public class LangChain4jDirectAnswerer implements DirectAnswerer {

    private final DirectAnswerServiceFactory directAnswerServiceFactory;
    private final PromptInputEncoder encoder;

    public LangChain4jDirectAnswerer(DirectAnswerServiceFactory directAnswerServiceFactory,
                                     PromptInputEncoder encoder) {
        this.directAnswerServiceFactory = directAnswerServiceFactory;
        this.encoder = encoder;
    }

    @Override
    public CompletionStage<String> answer(String question, ConversationHistorySnapshot history,
                                          Consumer<String> onToken) {
        long started = System.nanoTime();
        Map<String, String> context = LogContextUtils.snapshot();
        CompletableFuture<String> result = new CompletableFuture<>();
        StringBuilder full = new StringBuilder();
        TokenStream stream;
        try {
            stream = directAnswerServiceFactory.directAnswerService()
                    .answer(encoder.history(history), encoder.text(question));
        } catch (RuntimeException e) {
            log.warn("AI call failed: operation=direct-answer, reasonCode=STREAM_SETUP");
            result.completeExceptionally(e);
            return result;
        }
        stream.onPartialResponse(token -> {
                    if (result.isDone()) {
                        return;
                    }
                    try (var ignored = LogContextUtils.install(context)) {
                        full.append(token);
                        onToken.accept(token);
                    }
                })
                .onCompleteResponse(response -> {
                    try (var ignored = LogContextUtils.install(context)) {
                        log.info("AI call completed: operation=direct-answer, elapsedMs={}",
                                (System.nanoTime() - started) / 1_000_000);
                        result.complete(full.toString());
                    }
                })
                .onError(error -> {
                    try (var ignored = LogContextUtils.install(context)) {
                        log.warn("AI call failed: operation=direct-answer, elapsedMs={}, errorType={}",
                                (System.nanoTime() - started) / 1_000_000,
                                error == null ? "unknown" : error.getClass().getSimpleName());
                        result.completeExceptionally(error == null
                                ? new IllegalStateException("Streaming answer failed") : error);
                    }
                })
                .start();
        return result;
    }
}
