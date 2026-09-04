package com.chh.autosense.contract;

import com.chh.autosense.api.ApiException;
import com.chh.autosense.api.ErrorCode;
import com.chh.autosense.controller.SessionController;
import com.chh.autosense.api.sse.SseEvent;
import com.chh.autosense.api.sse.SseEventStream;
import com.chh.autosense.security.AuthUser;
import com.chh.autosense.security.BearerTokenAuthFilter;
import com.chh.autosense.security.SecurityConfig;
import com.chh.autosense.security.UserTokenResolver;
import com.chh.autosense.session.SessionOrchestrator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T018:API 契约测试(contracts §契约测试要点,2026-08-27 SSE 化)——
 * 401/403/400 走 JSON 错误体;409/422 与等待态/终态走 SSE 事件(error/awaiting/conclusion)。
 * 编排层以 mock 替代,聚焦 HTTP/SSE 契约与事件序列。
 */
@WebMvcTest(SessionController.class)
@Import({SecurityConfig.class, BearerTokenAuthFilter.class})
class SessionApiContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SessionOrchestrator orchestrator;

    @MockitoBean
    private UserTokenResolver tokenResolver;

    @MockitoBean(name = "applicationTaskExecutor")
    private TaskExecutor taskExecutor;

    @BeforeEach
    void setUp() {
        doAnswer(inv -> {
            inv.<Runnable>getArgument(0).run();
            return null;
        }).when(taskExecutor).execute(any(Runnable.class));
        when(tokenResolver.resolve(anyString()))
                .thenAnswer(inv -> {
                    String header = inv.getArgument(0);
                    return header.contains("user-1") ? new AuthUser(1L) : null;
                });
    }

    @Test
    void 未带令牌返回401() throws Exception {
        mockMvc.perform(post("/api/v1/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"problem\":\"灯不亮了\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void 越权访问返回403() throws Exception {
        when(orchestrator.getSession(any(), eq(999L)))
                .thenThrow(new ApiException(ErrorCode.DEVICE_FORBIDDEN, "无权访问该会话", 999L));

        mockMvc.perform(get("/api/v1/sessions/999")
                        .header("Authorization", "Bearer user-1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DEVICE_FORBIDDEN"))
                .andExpect(jsonPath("$.sessionId").value(999));
    }

    @Test
    void 缺少problem字段返回400() throws Exception {
        mockMvc.perform(post("/api/v1/sessions")
                        .header("Authorization", "Bearer user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void 设备互斥通过error事件传达_流随后关闭() throws Exception {
        doAnswer(inv -> {
            SseEventStream stream = inv.getArgument(2);
            stream.error(ErrorCode.DEVICE_BUSY.name(), "该设备正在处理中,请稍后再试。", 1L);
            return null;
        }).when(orchestrator).createSession(any(), anyString(), any());

        String body = sseBody(mockMvc.perform(post("/api/v1/sessions")
                        .header("Authorization", "Bearer user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"problem\":\"客厅的灯不亮了\"}"))
                .andReturn());

        assertThat(body).contains("event:error");
        assertThat(body).contains("DEVICE_BUSY");
    }

    @Test
    void 不支持设备类型通过error事件传达() throws Exception {
        doAnswer(inv -> {
            SseEventStream stream = inv.getArgument(2);
            stream.error(ErrorCode.UNSUPPORTED_DEVICE_TYPE.name(), "暂不支持该设备类型", 1L);
            return null;
        }).when(orchestrator).createSession(any(), anyString(), any());

        String body = sseBody(mockMvc.perform(post("/api/v1/sessions")
                        .header("Authorization", "Bearer user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"problem\":\"扫地机器人不工作了\"}"))
                .andReturn());

        assertThat(body).contains("event:error");
        assertThat(body).contains("UNSUPPORTED_DEVICE_TYPE");
    }

    @Test
    void 中断型修复停留在确认门_awaiting事件后关流() throws Exception {
        doAnswer(inv -> {
            SseEventStream stream = inv.getArgument(2);
            stream.send(SseEvent.status(1L, "CONFIRMING_REPAIR"));
            stream.awaitUser(1L, "该操作可能造成短暂服务中断,是否执行?");
            return null;
        }).when(orchestrator).createSession(any(), anyString(), any());

        String body = sseBody(mockMvc.perform(post("/api/v1/sessions")
                        .header("Authorization", "Bearer user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"problem\":\"灯太暗了,帮我调亮\"}"))
                .andReturn());

        assertThat(body).contains("event:status").contains("CONFIRMING_REPAIR");
        assertThat(body).contains("event:awaiting");
        assertThat(body).contains("该操作可能造成短暂服务中断,是否执行?");
    }

    @Test
    void 确认修复后流内推进至结论() throws Exception {
        doAnswer(inv -> {
            SseEventStream stream = inv.getArgument(4);
            stream.send(SseEvent.status(1L, "REPAIRING"));
            stream.conclude(Map.of("sessionId", 1L, "conclusionType", "FIXED",
                    "conclusionText", "已修复"));
            return null;
        }).when(orchestrator).postMessage(any(), eq(1L), anyString(), any(), any());

        String body = sseBody(mockMvc.perform(post("/api/v1/sessions/1/messages")
                        .header("Authorization", "Bearer user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"确认执行\",\"confirmRepair\":true}"))
                .andReturn());

        assertThat(body).contains("event:status").contains("REPAIRING");
        assertThat(body).contains("event:conclusion").contains("FIXED");
    }

    @Test
    void 常识问答以token流推送并以conclusion收尾() throws Exception {
        doAnswer(inv -> {
            SseEventStream stream = inv.getArgument(2);
            stream.send(SseEvent.status(1L, "ANSWERING"));
            stream.send(SseEvent.token("灯的"));
            stream.send(SseEvent.token("亮度"));
            stream.conclude(Map.of("sessionId", 1L, "conclusionType", "ANSWERED",
                    "conclusionText", "灯的亮度"));
            return null;
        }).when(orchestrator).createSession(any(), anyString(), any());

        String body = sseBody(mockMvc.perform(post("/api/v1/sessions")
                        .header("Authorization", "Bearer user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"problem\":\"灯泡亮度最高是多少?\"}"))
                .andReturn());

        assertThat(body).contains("event:token");
        assertThat(body.indexOf("event:status")).isLessThan(body.indexOf("event:token"));
        assertThat(body.indexOf("event:token")).isLessThan(body.indexOf("event:conclusion"));
    }

    @Test
    void 延迟产生的token到达前SSE不会被提前关闭() throws Exception {
        CountDownLatch releasePublisher = new CountDownLatch(1);
        AtomicReference<CompletableFuture<Void>> publisher = new AtomicReference<>();
        doAnswer(inv -> {
            SseEventStream stream = inv.getArgument(2);
            publisher.set(CompletableFuture.runAsync(() -> {
                try {
                    if (!releasePublisher.await(2, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("等待测试放行超时");
                    }
                    stream.send(SseEvent.token("延迟回答"));
                    stream.conclude(Map.of("type", "ANSWERED", "summary", "延迟回答"));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }));
            return null;
        }).when(orchestrator).createSession(any(), anyString(), any());

        MvcResult result = mockMvc.perform(post("/api/v1/sessions")
                        .header("Authorization", "Bearer user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"problem\":\"灯泡亮度最高是多少?\"}"))
                .andReturn();

        releasePublisher.countDown();
        String body = sseBody(result);
        publisher.get().get(2, TimeUnit.SECONDS);

        assertThat(body).contains("event:token").contains("延迟回答");
        assertThat(body).contains("event:conclusion").contains("ANSWERED");
    }

    /** 读取已完成 SSE 流的全部文本。 */
    private String sseBody(MvcResult mvcResult) throws Exception {
        return mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }
}
