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

    public LangChain4jDirectAnswerer(DirectAnswerServiceFactory directAnswerServiceFactory,
                                     PromptInputEncoder encoder) {
        this.directAnswerServiceFactory = directAnswerServiceFactory;
        this.encoder = encoder;
    }

    @Override
    public CompletionStage<String> answer(String question, ConversationHistorySnapshot history,
                                          Consumer<String> onToken) {
        AiCallLog call = AiCallLog.start("direct-answer");
        Map<String, String> context = LogContextUtils.snapshot();
        CompletableFuture<String> result = new CompletableFuture<>();
        StringBuilder full = new StringBuilder();
        TokenStream stream;
        try {
            String historyJson = encoder.history(history);
            String textJson = encoder.text(question);
            call.phase(AiCallLog.Phase.SERVICE_SETUP);
            var service = directAnswerServiceFactory.directAnswerService();
            stream = service.answer(historyJson, textJson);
        } catch (RuntimeException e) {
            call.failed(e);
            result.completeExceptionally(e);
            return result;
        }
        try {
            call.phase(AiCallLog.Phase.STREAM_START);
            stream.onPartialResponse(token -> {
                    if (result.isDone()) {
                        return;
                    }
                    try (var ignored = LogContextUtils.install(context)) {
                        call.firstResponse();
                        full.append(token);
                        onToken.accept(token);
                    } catch (RuntimeException e) {
                        if (result.completeExceptionally(e)) call.failed(e, AiCallLog.Phase.TOKEN_CALLBACK);
                    }
                })
                .onCompleteResponse(response -> {
                    try (var ignored = LogContextUtils.install(context)) {
                        if (result.complete(full.toString())) call.completed();
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
