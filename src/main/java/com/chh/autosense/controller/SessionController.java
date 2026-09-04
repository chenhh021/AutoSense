package com.chh.autosense.controller;

import com.chh.autosense.api.ApiException;
import com.chh.autosense.api.ErrorCode;
import com.chh.autosense.api.dto.ChatMessageView;
import com.chh.autosense.api.dto.CreateSessionRequest;
import com.chh.autosense.api.dto.MessageRequest;
import com.chh.autosense.api.dto.SessionListItemView;
import com.chh.autosense.api.dto.SessionResponse;
import com.chh.autosense.api.sse.SseEventStream;
import com.chh.autosense.domain.model.ChatMessage;
import com.chh.autosense.security.AuthUser;
import com.chh.autosense.session.SessionOrchestrator;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * 会话 API(contracts/diagnosis-api.md,T027,2026-08-27 SSE 化):
 * 写入端点(POST /sessions、POST /{id}/messages)返回 SSE 流(FR-021);
 * 查询端点返回 JSON(断线补查/非 SSE 客户端)。
 */
@RestController
@RequestMapping("/api/v1/sessions")
public class SessionController {

    private final SessionOrchestrator orchestrator;
    private final TaskExecutor taskExecutor;

    public SessionController(SessionOrchestrator orchestrator,
                             @Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor) {
        this.orchestrator = orchestrator;
        this.taskExecutor = taskExecutor;
    }

    /** POST 即 SSE 流(R18):事件由编排层推送,等待态/终态关流。 */
    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter create(@AuthenticationPrincipal AuthUser user,
                             @Valid @RequestBody CreateSessionRequest request) {
        SseEventStream stream = new SseEventStream();
        runAsync(stream, () -> orchestrator.createSession(user, request.problem(), stream));
        return stream.emitter();
    }

    @PostMapping(path = "/{sessionId}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter postMessage(@AuthenticationPrincipal AuthUser user,
                                  @PathVariable Long sessionId,
                                  @RequestBody MessageRequest request) {
        SseEventStream stream = new SseEventStream();
        runAsync(stream, () -> orchestrator.postMessage(user, sessionId, request.content(),
                request.confirmRepair(), stream));
        return stream.emitter();
    }

    @GetMapping("/{sessionId}")
    public SessionResponse get(@AuthenticationPrincipal AuthUser user,
                               @PathVariable Long sessionId) {
        return orchestrator.getSession(user, sessionId);
    }

    /** 历史对话列表(FR-018,contracts §5)。 */
    @GetMapping
    public Map<String, List<SessionListItemView>> list(@AuthenticationPrincipal AuthUser user) {
        return Map.of("sessions", orchestrator.listSessions(user));
    }

    @GetMapping("/{sessionId}/messages")
    public List<ChatMessageView> listMessages(@AuthenticationPrincipal AuthUser user,
                                              @PathVariable Long sessionId) {
        return orchestrator.listMessages(user, sessionId).stream()
                .map(this::toView)
                .toList();
    }

    /** 先返回 SseEmitter,再异步执行编排,使模型 token 能实时写入响应流。 */
    private void runAsync(SseEventStream stream, Runnable body) {
        try {
            taskExecutor.execute(() -> run(stream, body));
        } catch (RuntimeException e) {
            stream.error(ErrorCode.INTERNAL_ERROR.name(), "服务内部错误", null);
        }
    }

    /** 异常经 error 事件收尾;正常路径由 awaiting/conclusion 主动关流。 */
    private void run(SseEventStream stream, Runnable body) {
        try {
            body.run();
        } catch (ApiException e) {
            stream.error(e.errorCode().name(), e.getMessage(), e.sessionId());
        } catch (Exception e) {
            stream.error(ErrorCode.INTERNAL_ERROR.name(), "服务内部错误", null);
        }
    }

    private ChatMessageView toView(ChatMessage m) {
        return new ChatMessageView(m.getRole(), m.getContent(), m.getCreatedAt());
    }
}
