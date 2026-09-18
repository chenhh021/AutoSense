package com.chh.autosense.controller;

import com.chh.autosense.config.GraphProperties;
import com.chh.autosense.core.security.AuthUser;
import com.chh.autosense.core.session.*;
import com.chh.autosense.domain.dto.*;
import com.chh.autosense.domain.message.SseEventStream;
import com.chh.autosense.domain.vo.*;
import com.chh.autosense.exception.*;
import com.chh.autosense.graph.*;
import com.chh.autosense.utils.*;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.*;

/** Authenticated admission precedes SSE; only graph snapshots provide live workflow output. */
@RestController
@RequestMapping("/api/v1/sessions")
@Slf4j
public class SessionController {
    private final WorkflowExecutionService executions;
    private final ConversationQueryService conversations;
    private final TaskExecutor taskExecutor;
    private final GraphProperties properties;
    public SessionController(WorkflowExecutionService executions, ConversationQueryService conversations,
            @Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor, GraphProperties properties) {
        this.executions = executions; this.conversations = conversations; this.taskExecutor = taskExecutor; this.properties = properties;
    }
    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "SSE workflow events; each data frame contains a WorkflowEvent",
            content = @io.swagger.v3.oas.annotations.media.Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
                    schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = com.chh.autosense.domain.message.WorkflowEvent.class)))
    public SseEmitter create(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody CreateSessionRequest request) throws Exception {
        return stream(executions.create(user, null, request.problem()));
    }
    @PostMapping(path = "/{sessionId}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter postMessage(@AuthenticationPrincipal AuthUser user, @PathVariable long sessionId,
            @Valid @RequestBody MessageRequest request) throws Exception {
        return stream(executions.message(user, sessionId, request));
    }
    @PostMapping(path = "/{sessionId}/workflows/{requestId}/approval", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter approval(@AuthenticationPrincipal AuthUser user, @PathVariable long sessionId, @PathVariable String requestId,
            @Valid @RequestBody WorkflowApprovalRequest request) throws Exception {
        return stream(executions.approval(user, sessionId, requestId, request));
    }
    @PostMapping(path = "/{sessionId}/workflows/{requestId}/resume", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter resume(@AuthenticationPrincipal AuthUser user, @PathVariable long sessionId, @PathVariable String requestId,
            @Valid @RequestBody WorkflowResumeRequest request) throws Exception {
        return stream(executions.resume(user, sessionId, requestId, request.expectedVersion()));
    }
    @PostMapping(path = "/{sessionId}/workflows/{requestId}/cancel", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter cancel(@AuthenticationPrincipal AuthUser user, @PathVariable long sessionId, @PathVariable String requestId,
            @Valid @RequestBody WorkflowCancelRequest request) throws Exception {
        return stream(executions.cancel(user, sessionId, requestId, request.expectedVersion()));
    }
    @GetMapping("/{sessionId}/workflows/{requestId}")
    public WorkflowView workflow(@AuthenticationPrincipal AuthUser user, @PathVariable long sessionId, @PathVariable String requestId) {
        return conversations.workflow(user, sessionId, requestId);
    }
    @GetMapping("/{sessionId}")
    public SessionResponse get(@AuthenticationPrincipal AuthUser user, @PathVariable long sessionId) { return conversations.get(user, sessionId); }
    @GetMapping
    public Map<String, List<SessionListItemView>> list(@AuthenticationPrincipal AuthUser user) { return Map.of("sessions", conversations.list(user)); }
    @GetMapping("/{sessionId}/messages")
    public List<ChatMessageView> messages(@AuthenticationPrincipal AuthUser user, @PathVariable long sessionId) {
        return conversations.messages(user, sessionId).stream().map(m -> new ChatMessageView(m.getRole(), m.getContent(), m.getCreatedAt())).toList();
    }
    @DeleteMapping("/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthUser user, @PathVariable long sessionId) { conversations.delete(user, sessionId); }

    private SseEmitter stream(WorkflowExecutionService.Run run) {
        var stream = new SseEventStream((properties.executionSliceTimeoutSeconds() + 30L) * 1000);
        var context = LogContextUtils.snapshot();
        try {
            taskExecutor.execute(() -> {
                try (run; var ignored = LogContextUtils.install(context)) {
                    try {
                        new WorkflowEventStream().consume(run.stream(), event -> new WorkflowEventProjector().send(event, stream));
                        stream.complete();
                    } catch (Exception e) {
                        try { run.failed(); }
                        catch (RuntimeException recoveryError) {
                            log.error("Workflow suspension failed: errorCode=INTERNAL_ERROR", LogSanitizer.diagnostic(recoveryError));
                        }
                        log.error("Workflow stream failed: errorCode=INTERNAL_ERROR", LogSanitizer.diagnostic(e));
                        stream.error(ErrorCode.INTERNAL_ERROR.name(), "处理暂时中断，请查询当前状态。", null);
                    }
                }
            });
        } catch (RuntimeException e) {
            try { run.failed(); }
            catch (RuntimeException recoveryError) { log.error("Workflow suspension failed: errorCode=INTERNAL_ERROR", LogSanitizer.diagnostic(recoveryError)); }
            finally { run.close(); }
            log.error("Workflow task submission failed: errorCode=INTERNAL_ERROR", LogSanitizer.diagnostic(e));
            stream.error(ErrorCode.INTERNAL_ERROR.name(), "服务暂时不可用，请查询当前状态。", null);
        }
        return stream.emitter();
    }
}
