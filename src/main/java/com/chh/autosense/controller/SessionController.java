package com.chh.autosense.controller;

import com.chh.autosense.config.AssistantProperties;
import com.chh.autosense.exception.ApiException;
import com.chh.autosense.exception.ErrorCode;
import com.chh.autosense.domain.vo.ChatMessageView;
import com.chh.autosense.domain.dto.CreateSessionRequest;
import com.chh.autosense.domain.dto.MessageRequest;
import com.chh.autosense.domain.vo.SessionListItemView;
import com.chh.autosense.domain.dto.SessionResponse;
import com.chh.autosense.domain.message.SseEventStream;
import com.chh.autosense.domain.entity.ChatMessage;
import com.chh.autosense.core.security.AuthUser;
import com.chh.autosense.core.session.SessionOrchestrator;
import com.chh.autosense.utils.LogContextUtils;
import com.chh.autosense.utils.LogSanitizer;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * 会话 API（assistant-api §1）：写入端点返回 SSE 流；查询端点返回 JSON（断线补查）。
 * 返回 emitter 仅表示请求已接受；业务结果以持久化事实为准，不在此记录完成。
 */
@RestController
@RequestMapping("/api/v1/sessions")
@Slf4j
public class SessionController {

    private final SessionOrchestrator orchestrator;
    private final TaskExecutor taskExecutor;
    private final AssistantProperties properties;

    public SessionController(SessionOrchestrator orchestrator,
                             @Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor,
                             AssistantProperties properties) {
        this.orchestrator = orchestrator;
        this.taskExecutor = taskExecutor;
        this.properties = properties;
    }

    /**
     * 创建会话并以 SSE 流式返回处理过程与结果
     *
     * @param user    Spring Security 注入的当前登录用户
     * @param request 创建请求，包含初始问题 problem
     * @return SSE 事件流；返回 emitter 仅表示请求已接受，业务结果以持久化事实为准
     */
    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter create(@AuthenticationPrincipal AuthUser user,
                             @Valid @RequestBody CreateSessionRequest request) {
        SseEventStream stream = newStream();
        runAsync(stream, () -> orchestrator.createSession(user, request.problem(), stream));
        return stream.emitter();
    }

    /**
     * 在已有会话中追加一轮消息并以 SSE 流式返回
     *
     * @param user      Spring Security 注入的当前登录用户
     * @param sessionId 会话 ID
     * @param request   消息请求，包含内容 content 与修复确认标记 confirmRepair
     * @return SSE 事件流
     */
    @PostMapping(path = "/{sessionId}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter postMessage(@AuthenticationPrincipal AuthUser user,
                                  @PathVariable Long sessionId,
                                  @RequestBody MessageRequest request) {
        SseEventStream stream = newStream();
        runAsync(stream, () -> orchestrator.postMessage(user, sessionId, request.content(),
                request.confirmRepair(), stream));
        return stream.emitter();
    }

    /**
     * 获取会话详情（断线补查）
     *
     * @param user      Spring Security 注入的当前登录用户
     * @param sessionId 会话 ID
     * @return 会话状态、回复与终态结论；awaitingInput=true 表示等待用户继续
     */
    @GetMapping("/{sessionId}")
    public SessionResponse get(@AuthenticationPrincipal AuthUser user,
                               @PathVariable Long sessionId) {
        return orchestrator.getSession(user, sessionId);
    }

    /**
     * 获取当前用户的会话列表
     *
     * @param user Spring Security 注入的当前登录用户
     * @return 会话摘要列表（键 sessions）
     */
    @GetMapping
    public Map<String, List<SessionListItemView>> list(@AuthenticationPrincipal AuthUser user) {
        return Map.of("sessions", orchestrator.listSessions(user));
    }

    /**
     * 获取会话的完整消息历史（时间升序，断线补查）
     *
     * @param user      Spring Security 注入的当前登录用户
     * @param sessionId 会话 ID
     * @return 消息视图列表
     */
    @GetMapping("/{sessionId}/messages")
    public List<ChatMessageView> listMessages(@AuthenticationPrincipal AuthUser user,
                                              @PathVariable Long sessionId) {
        return orchestrator.listMessages(user, sessionId).stream()
                .map(this::toView)
                .toList();
    }

    /**
     * 删除会话及其全部消息与处理记录，并清除会话上下文
     *
     * @param user      Spring Security 注入的当前登录用户
     * @param sessionId 会话 ID
     */
    @DeleteMapping("/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthUser user,
                       @PathVariable Long sessionId) {
        orchestrator.deleteSession(user, sessionId);
    }

    /**
     * 创建 SSE 事件流，超时与公共处理截止协调（处理截止 + 租约宽限），不使用独立的更短硬编码超时
     *
     * @return 配置好超时的 SSE 事件流
     */
    private SseEventStream newStream() {
        long timeoutMs = (properties.processingTimeoutSeconds()
                + properties.sessionLeaseSeconds()) * 1000L;
        return new SseEventStream(timeoutMs);
    }

    /**
     * 异步执行编排：先返回 SseEmitter，提交被拒绝时立即以错误事件关流
     *
     * @param stream SSE 事件流
     * @param body   编排逻辑
     */
    private void runAsync(SseEventStream stream, Runnable body) {
        Map<String, String> context = LogContextUtils.snapshot();
        try {
            taskExecutor.execute(() -> {
                try (var ignored = LogContextUtils.install(context)) {
                    run(stream, body);
                }
            });
        } catch (RuntimeException e) {
            log.error("Request failed: errorCode=INTERNAL_ERROR, reasonCode=TASK_SUBMISSION",
                    LogSanitizer.diagnostic(e));
            stream.error(ErrorCode.INTERNAL_ERROR.name(), "服务内部错误", null);
        }
    }

    /**
     * 执行编排并统一收尾：异常经 error 事件收尾，正常路径由 awaiting/conclusion 主动关流
     *
     * @param stream SSE 事件流
     * @param body   编排逻辑
     */
    private void run(SseEventStream stream, Runnable body) {
        try {
            body.run();
        } catch (ApiException e) {
            stream.error(e.errorCode().name(), e.getMessage(), e.sessionId());
        } catch (Exception e) {
            log.error("Request failed: errorCode=INTERNAL_ERROR", LogSanitizer.diagnostic(e));
            stream.error(ErrorCode.INTERNAL_ERROR.name(), "服务内部错误", null);
        }
    }

    /**
     * 将消息实体转为对外视图
     *
     * @param m 消息实体
     * @return 消息视图
     */
    private ChatMessageView toView(ChatMessage m) {
        return new ChatMessageView(m.getRole(), m.getContent(), m.getCreatedAt());
    }
}
