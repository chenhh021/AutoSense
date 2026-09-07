package com.chh.autosense.unit;

import com.chh.autosense.ai.DirectAnswerService;
import com.chh.autosense.ai.factory.DirectAnswerServiceFactory;
import com.chh.autosense.core.routing.DirectAnswerer;
import com.chh.autosense.core.routing.LangChain4jDirectAnswerer;
import com.chh.autosense.core.session.memory.ConversationHistorySnapshot;
import com.chh.autosense.utils.PromptInputEncoder;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolExecution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TokenStream → CompletionStage 适配：token 逐段回调、同步完成携带完整文本、
 * 失败异常完成且绝不生成兜底成功回答。
 */
class LangChain4jDirectAnswererTest {

    private final DirectAnswerServiceFactory factory = mock(DirectAnswerServiceFactory.class);
    private final FakeTokenStream stream = new FakeTokenStream();
    private final ConversationHistorySnapshot history = new ConversationHistorySnapshot(1, 2, List.of());
    private DirectAnswerer answerer;

    @BeforeEach
    void setUp() {
        DirectAnswerService service = mock(DirectAnswerService.class);
        when(service.answer(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(stream);
        when(factory.directAnswerService()).thenReturn(service);
        answerer = new LangChain4jDirectAnswerer(factory, new PromptInputEncoder());
    }

    @Test
    void 完整回答只在流式回调完成后返回() {
        List<String> tokens = new ArrayList<>();

        CompletionStage<String> result = answerer.answer("灯泡寿命多久?", history, tokens::add);

        assertThat(result.toCompletableFuture()).isNotDone();
        stream.partial.accept("大约");
        stream.partial.accept("五年");
        assertThat(result.toCompletableFuture()).isNotDone();
        ChatResponse response = mock(ChatResponse.class);
        when(response.aiMessage()).thenReturn(AiMessage.from("大约五年"));
        stream.complete.accept(response);

        assertThat(result.toCompletableFuture()).isCompletedWithValue("大约五年");
        assertThat(tokens).containsExactly("大约", "五年");
    }

    @Test
    void 模型流失败时异常完成且不产生兜底回答() {
        List<String> tokens = new ArrayList<>();

        CompletionStage<String> result = answerer.answer("灯泡寿命多久?", history, tokens::add);
        stream.partial.accept("片段");
        stream.error.accept(new IllegalStateException("upstream failed"));

        assertThat(result.toCompletableFuture()).isCompletedExceptionally();
        assertThatThrownBy(() -> result.toCompletableFuture().join())
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThat(tokens).containsExactly("片段");
    }

    @Test
    void 完成后再到的token不会重复回调() {
        List<String> tokens = new ArrayList<>();
        CompletionStage<String> result = answerer.answer("灯泡寿命多久?", history, tokens::add);
        stream.partial.accept("完成");
        ChatResponse response = mock(ChatResponse.class);
        when(response.aiMessage()).thenReturn(AiMessage.from("完成"));
        stream.complete.accept(response);
        stream.partial.accept("迟到");

        assertThat(result.toCompletableFuture()).isCompletedWithValue("完成");
        assertThat(tokens).containsExactly("完成");
    }

    /** 手动驱动的 TokenStream：回调由测试触发，start 不做任何事。 */
    private static final class FakeTokenStream implements TokenStream {
        private Consumer<String> partial = token -> { };
        private Consumer<ChatResponse> complete = response -> { };
        private Consumer<Throwable> error = error -> { };

        @Override public TokenStream onPartialResponse(Consumer<String> consumer) {
            this.partial = consumer;
            return this;
        }

        @Override public TokenStream onRetrieved(Consumer<List<Content>> consumer) {
            return this;
        }

        @Override public TokenStream onToolExecuted(Consumer<ToolExecution> consumer) {
            return this;
        }

        @Override public TokenStream onCompleteResponse(Consumer<ChatResponse> consumer) {
            this.complete = consumer;
            return this;
        }

        @Override public TokenStream onError(Consumer<Throwable> consumer) {
            this.error = consumer;
            return this;
        }

        @Override public TokenStream ignoreErrors() {
            return this;
        }

        @Override public void start() { }
    }
}
