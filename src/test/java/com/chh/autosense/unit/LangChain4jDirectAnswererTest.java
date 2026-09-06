package com.chh.autosense.unit;

import com.chh.autosense.config.LangChain4jConfig;
import com.chh.autosense.core.routing.DirectAnswerer;
import com.chh.autosense.core.session.memory.ChatMemoryFactory;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LangChain4jDirectAnswererTest {

    private final OpenAiStreamingChatModel streamingModel = mock(OpenAiStreamingChatModel.class);
    private final ChatMemoryFactory memoryFactory = mock(ChatMemoryFactory.class);
    private final ChatMemory memory = mock(ChatMemory.class);
    private final AtomicReference<StreamingChatResponseHandler> handler = new AtomicReference<>();
    private DirectAnswerer answerer;

    @BeforeEach
    void setUp() {
        when(memoryFactory.forSession(42L)).thenReturn(memory);
        when(memory.messages()).thenReturn(List.of());
        doAnswer(inv -> {
            handler.set(inv.getArgument(1));
            return null;
        }).when(streamingModel).chat(anyList(), any(StreamingChatResponseHandler.class));
        answerer = new LangChain4jConfig()
                .langChain4jDirectAnswerer(streamingModel, memoryFactory);
    }

    @Test
    void 完整回答只在流式回调完成后返回() {
        List<String> tokens = new ArrayList<>();

        CompletionStage<String> result = answerer.answer("灯泡寿命多久?", 42L, tokens::add);

        assertThat(result.toCompletableFuture()).isNotDone();
        handler.get().onPartialResponse("大约");
        handler.get().onPartialResponse("五年");
        assertThat(result.toCompletableFuture()).isNotDone();
        handler.get().onCompleteResponse(mock(ChatResponse.class));

        assertThat(result.toCompletableFuture()).isCompletedWithValue("大约五年");
        assertThat(tokens).containsExactly("大约", "五年");
        verify(memory, times(2)).add(any(ChatMessage.class));
    }

    @Test
    void 模型流失败时返回可见的兜底回答() {
        List<String> tokens = new ArrayList<>();

        CompletionStage<String> result = answerer.answer("灯泡寿命多久?", 42L, tokens::add);
        handler.get().onError(new IllegalStateException("upstream failed"));

        assertThat(result.toCompletableFuture())
                .isCompletedWithValue("回答生成遇到问题,请稍后再试。");
        assertThat(tokens).containsExactly("回答生成遇到问题,请稍后再试。");
    }
}
