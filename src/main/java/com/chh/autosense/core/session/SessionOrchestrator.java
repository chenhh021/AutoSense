package com.chh.autosense.core.session;

import com.chh.autosense.ai.model.RoutingDecision;
import com.chh.autosense.ai.model.enums.RoutingOutcome;
import com.chh.autosense.config.AssistantProperties;
import com.chh.autosense.core.routing.CapabilityDispatcher;
import com.chh.autosense.core.routing.CapabilityRequest;
import com.chh.autosense.core.routing.CapabilityResult;
import com.chh.autosense.core.routing.IntentClassifier;
import com.chh.autosense.core.routing.RoutingDecisionValidator;
import com.chh.autosense.core.security.AuthUser;
import com.chh.autosense.core.session.SessionProcessingService.Accepted;
import com.chh.autosense.core.session.memory.ConversationHistoryService;
import com.chh.autosense.core.session.memory.ConversationHistorySnapshot;
import com.chh.autosense.domain.dto.ConclusionDto;
import com.chh.autosense.domain.vo.SessionListItemView;
import com.chh.autosense.domain.dto.SessionResponse;
import com.chh.autosense.domain.entity.ChatMessage;
import com.chh.autosense.domain.entity.DiagnosticSnapshot;
import com.chh.autosense.domain.entity.RepairSession;
import com.chh.autosense.domain.enums.AssistantCapability;
import com.chh.autosense.domain.enums.SessionStatus;
import com.chh.autosense.domain.enums.SnapshotPhase;
import com.chh.autosense.domain.message.SseEvent;
import com.chh.autosense.domain.message.SseEventStream;
import com.chh.autosense.exception.ApiException;
import com.chh.autosense.exception.ErrorCode;
import com.chh.autosense.mapper.ChatMessageMapper;
import com.chh.autosense.mapper.DiagnosticSnapshotMapper;
import com.chh.autosense.mapper.RepairSessionMapper;
import com.chh.autosense.utils.LogContextUtils;
import com.chh.autosense.utils.LogSanitizer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybatisflex.core.query.QueryWrapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 公共会话编排（routing-contract §3）：接纳一次 USER 消息与固定历史边界，经真实 AI 分类、
 * 服务端校验后只向已注册能力分发。公共路由本身零设备读写，无旧 DEVICE_ACTION 回退；
 * 澄清/复合/范围外由公共入口直接收尾，缺失能力明确失败。
 * 异步生命周期：30 秒 owner 租约按 10 秒续期至持久化结束；固定总截止到期主动结清并关流；
 * 失租约停止后续 token 与结果回调；所有持久化收尾经 SessionProcessingService 指针校验。
 */
@Slf4j
@Service
public class SessionOrchestrator {

    private static final String OUT_OF_SCOPE_REPLY =
            "AutoSense 可以帮助咨询产品知识、查询您的设备、诊断设备问题和安全地控制设备。";

    private final SessionProcessingService processing;
    private final ConversationHistoryService historyService;
    private final IntentClassifier intentClassifier;
    private final RoutingDecisionValidator validator;
    private final CapabilityDispatcher dispatcher;
    private final SessionLeaseService leases;
    private final SessionContextStore contextStore;
    private final AssistantProperties properties;
    private final RepairSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final DiagnosticSnapshotMapper snapshotMapper;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService scheduler;

    public SessionOrchestrator(SessionProcessingService processing,
                               ConversationHistoryService historyService,
                               IntentClassifier intentClassifier,
                               RoutingDecisionValidator validator,
                               CapabilityDispatcher dispatcher,
                               SessionLeaseService leases,
                               SessionContextStore contextStore,
                               AssistantProperties properties,
                               RepairSessionMapper sessionMapper,
                               ChatMessageMapper messageMapper,
                               DiagnosticSnapshotMapper snapshotMapper,
                               ObjectMapper objectMapper) {
        this.processing = processing;
        this.historyService = historyService;
        this.intentClassifier = intentClassifier;
        this.validator = validator;
        this.dispatcher = dispatcher;
        this.leases = leases;
        this.contextStore = contextStore;
        this.properties = properties;
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.snapshotMapper = snapshotMapper;
        this.objectMapper = objectMapper;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "session-processing-guard");
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    // ========== 入口 1：发起会话（assistant-api §1，SSE 流） ==========

    /**
     * 创建会话并异步处理首条用户消息，结果经 SSE 流推送
     *
     * @param user        当前登录用户
     * @param problemText 首条问题文本
     * @param stream      SSE 事件流（status/token/awaiting/conclusion/error）
     */
    public void createSession(AuthUser user, String problemText, SseEventStream stream) {
        Accepted accepted = processing.create(user, problemText);
        process(user, accepted, stream);
    }

    // ========== 入口 2：会话内消息（assistant-api §1，SSE 流） ==========

    /**
     * 在已有会话中追加一轮用户消息并异步处理，结果经 SSE 流推送
     *
     * @param user          当前登录用户
     * @param sessionId     会话 ID
     * @param content       本轮消息内容
     * @param confirmRepair 修复确认标记（中断型操作的确认门输入）
     * @param stream        SSE 事件流
     */
    public void postMessage(AuthUser user, Long sessionId, String content,
                            Boolean confirmRepair, SseEventStream stream) {
        Accepted accepted = processing.accept(user, sessionId, content, confirmRepair);
        process(user, accepted, stream);
    }

    // ========== 入口 3：查询（JSON 补查；过期处理在此补偿结清） ==========

    /**
     * 获取会话详情（断线补查入口；若处理截止已过则先补偿结清再返回）
     *
     * @param user      当前登录用户
     * @param sessionId 会话 ID
     * @return 会话状态、当前回复与终态结论（仅终态携带 conclusion）
     */
    public SessionResponse getSession(AuthUser user, Long sessionId) {
        RepairSession session = processing.recoverOwned(user, sessionId);
        SessionStatus status = SessionStatus.valueOf(session.getStatus());
        return new SessionResponse(session.getId(), status.name(),
                replyFor(session, status), status.isAwaitingUser(),
                status.isTerminal() ? buildConclusion(session) : null);
    }

    /**
     * 获取会话完整消息历史（时间升序；同样先补偿结清过期处理）
     *
     * @param user      当前登录用户
     * @param sessionId 会话 ID
     * @return 消息实体列表（created_at、id 升序）
     */
    public List<ChatMessage> listMessages(AuthUser user, Long sessionId) {
        processing.recoverOwned(user, sessionId);
        return messageMapper.selectListByQuery(QueryWrapper.create()
                .where("session_id = ?", sessionId)
                .orderBy("created_at", true).orderBy("id", true));
    }

    /** 历史对话列表：仅本人，updatedAt 倒序；preview 取最近结论，无结论回退首条用户问题。 */
    public List<SessionListItemView> listSessions(AuthUser user) {
        return sessionMapper.selectListByQuery(QueryWrapper.create()
                        .where("user_id = ?", user.userId())
                        .orderBy("updated_at", false))
                .stream()
                .map(s -> new SessionListItemView(s.getId(), s.getStatus(),
                        previewOf(s), s.getCreatedAt(), s.getUpdatedAt()))
                .toList();
    }

    // ========== 入口 4：删除会话（级联删除 + 上下文清除） ==========

    /**
     * 删除会话：单事务级联删除全部子表数据后清除 Redis 会话上下文；
     * 归属与忙守卫在 deleteOwned 内完成（404/403/409）
     *
     * @param user      当前登录用户
     * @param sessionId 会话 ID
     */
    public void deleteSession(AuthUser user, Long sessionId) {
        processing.deleteOwned(user, sessionId);
        contextStore.evict(sessionId);
    }

    // ========== 公共处理链 ==========

    /**
     * 公共处理链入口：按上一轮状态分派——等待用户输入的状态（澄清除外）续走原能力，
     * 其余（含 CLARIFYING 与新轮）重新走意图路由；统一兜底异常并失败收尾
     *
     * @param user     当前登录用户
     * @param accepted 已接纳的本轮消息（含会话指针、轮次与处理截止）
     * @param stream   SSE 事件流
     */
    private void process(AuthUser user, Accepted accepted, SseEventStream stream) {
        Guard guard = new Guard(accepted, stream);
        try (var ignored = LogContextUtils.install(accepted.logContext())) {
            SessionStatus previous = accepted.previousStatus();
            if (previous.isAwaitingUser() && previous != SessionStatus.CLARIFYING) {
                continueWaiting(user, accepted, stream, guard, previous);
                return;
            }
            route(user, accepted, stream, guard);
        } catch (ApiException e) {
            failAccepted(accepted, stream, guard, e.errorCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Session finalization failed: sessionId={}, messageId={}, errorCode=INTERNAL_ERROR",
                    accepted.sessionId(), accepted.messageId(), LogSanitizer.diagnostic(e));
            failAccepted(accepted, stream, guard, ErrorCode.INTERNAL_ERROR, "服务内部错误，请稍后再试。");
        }
    }

    /**
     * 意图路由：AI 分类结果经服务端校验后分三支——澄清/复合由公共入口直接追问收尾，
     * 范围外给固定答复收尾，其余向已注册能力分发并挂异步结果回调；
     * 能力分发前经 advance 推进状态指针，推进失败说明存在更新轮次，直接放弃本轮
     *
     * @param user     当前登录用户
     * @param accepted 已接纳的本轮消息
     * @param stream   SSE 事件流
     * @param guard    本轮处理守护（租约 + 截止）
     */
    private void route(AuthUser user, Accepted accepted, SseEventStream stream, Guard guard) {
        stream.send(SseEvent.status(accepted.sessionId(), SessionStatus.ROUTING.name()));
        ConversationHistorySnapshot history = historyService.snapshot(accepted);
        RoutingDecision decision = validator.validate(intentClassifier.classify(accepted.text(), history));
        if (decision.outcome() == RoutingOutcome.CLARIFY || decision.outcome() == RoutingOutcome.COMPOSITE) {
            processing.advance(accepted, SessionStatus.ROUTING, decision.outcome().name());
            String prompt = decision.clarifyQuestion();
            if (processing.finish(accepted, SessionStatus.CLARIFYING, prompt, null, null)) {
                stream.awaitUser(accepted.sessionId(), prompt);
            } else {
                log.debug("Stale callback ignored: sessionId={}, messageId={}",
                        accepted.sessionId(), accepted.messageId());
            }
            guard.complete();
            return;
        }
        if (decision.outcome() == RoutingOutcome.OUT_OF_SCOPE) {
            log.info("Request out of scope: sessionId={}, round={}", accepted.sessionId(), accepted.round());
            processing.advance(accepted, SessionStatus.ANSWERING, RoutingOutcome.OUT_OF_SCOPE.name());
            ConclusionDto conclusion = new ConclusionDto("ANSWERED", OUT_OF_SCOPE_REPLY,
                    null, null, null, null);
            if (processing.finish(accepted, SessionStatus.COMPLETED_ANSWERED,
                    OUT_OF_SCOPE_REPLY, conclusion, null)) {
                stream.conclude(conclusion);
            }
            guard.complete();
            return;
        }
        AssistantCapability capability = validator.capability(decision);
        if (!processing.advance(accepted, SessionStatus.DISPATCHING, capability.name())) {
            guard.complete();
            return;
        }
        stream.send(SseEvent.status(accepted.sessionId(), SessionStatus.DISPATCHING.name()));
        CapabilityRequest request = new CapabilityRequest(user, accepted.sessionId(), accepted.reportId(),
                accepted.round(), accepted.messageId(), accepted.text(), accepted.confirmRepair(), history,
                accepted.deadline(), capability, decision.diagnosisMode(), decision.targetHint(), null,
                decision.requiresKnowledgeBase());
        CompletionStage<CapabilityResult> future;
        try {
            future = dispatcher.dispatch(request, guard.sink());
        } catch (ApiException e) {
            failAccepted(accepted, stream, guard, e.errorCode(), e.getMessage());
            return;
        } catch (RuntimeException e) {
            failAccepted(accepted, stream, guard, ErrorCode.INTERNAL_ERROR, "服务内部错误，请稍后再试。");
            return;
        }
        future.whenComplete((result, error) -> {
            try (var ignored = LogContextUtils.install(accepted.logContext())) {
                settle(user, accepted, stream, guard, capability, result, error, false);
            }
        });
    }

    /**
     * 续走等待态会话：从 Redis 上下文恢复原能力与轮次，校验归属与轮次一致后继续分发；
     * 上下文缺失/失效则以 CONTEXT_EXPIRED 失败收尾，要求用户重新提问
     *
     * @param user         当前登录用户
     * @param accepted     已接纳的本轮消息
     * @param stream       SSE 事件流
     * @param guard        本轮处理守护
     * @param waitingState 上一轮的等待状态（DEVICE_CONFIRMING/CONFIRMING_REPAIR/AWAITING_LOCATION）
     */
    private void continueWaiting(AuthUser user, Accepted accepted, SseEventStream stream,
                                 Guard guard, SessionStatus waitingState) {
        SessionContext context = contextStore.load(accepted.sessionId());
        AssistantCapability capability = null;
        if (context != null && Objects.equals(context.userId(), user.userId())
                && Objects.equals(context.round(), accepted.round()) && context.capability() != null) {
            try {
                capability = AssistantCapability.valueOf(context.capability());
            } catch (IllegalArgumentException ignored) {
                capability = null;
            }
        }
        if (capability == null) {
            log.warn("Session context expired: sessionId={}, waitingState={}",
                    accepted.sessionId(), waitingState);
            failAccepted(accepted, stream, guard, ErrorCode.CONTEXT_EXPIRED,
                    "原处理上下文已失效，请重新提问。");
            return;
        }
        AssistantCapability waitingCapability = capability;
        ConversationHistorySnapshot history = historyService.snapshot(accepted);
        CapabilityRequest request = new CapabilityRequest(user, accepted.sessionId(), accepted.reportId(),
                accepted.round(), accepted.messageId(), accepted.text(), accepted.confirmRepair(), history,
                accepted.deadline(), waitingCapability, null, null, waitingState, null);
        CompletionStage<CapabilityResult> future;
        try {
            future = dispatcher.continueWaiting(request, guard.sink());
        } catch (ApiException e) {
            failAccepted(accepted, stream, guard, e.errorCode(), e.getMessage());
            return;
        } catch (RuntimeException e) {
            failAccepted(accepted, stream, guard, ErrorCode.INTERNAL_ERROR, "服务内部错误，请稍后再试。");
            return;
        }
        future.whenComplete((result, error) -> {
            try (var ignored = LogContextUtils.install(accepted.logContext())) {
                settle(user, accepted, stream, guard, waitingCapability, result, error, true);
            }
        });
    }

    /**
     * 能力结果结算（异步回调）：按结果种类落库并推流——COMPLETE 写结论并关流、
     * WAIT 保存会话上下文并进入等待用户、FAIL 失败收尾、REROUTE 结清当前轮后以同文重开新轮；
     * finish 返回 false 表示回调迟到（轮次已被结清/删除），只记日志不再写库推流
     *
     * @param user         当前登录用户
     * @param accepted     已接纳的本轮消息
     * @param stream       SSE 事件流
     * @param guard        本轮处理守护
     * @param capability   本处分发的能力
     * @param result       能力结果（error 非空时为 null）
     * @param error        异步异常（CompletionException 取根因）
     * @param continuation 是否为等待态续走（REROUTE 仅允许来自续走）
     */
    private void settle(AuthUser user, Accepted accepted, SseEventStream stream, Guard guard,
                        AssistantCapability capability, CapabilityResult result, Throwable error,
                        boolean continuation) {
        if (error != null) {
            Throwable cause = error instanceof CompletionException && error.getCause() != null
                    ? error.getCause() : error;
            ErrorCode code = cause instanceof ApiException api ? api.errorCode() : ErrorCode.INTERNAL_ERROR;
            failAccepted(accepted, stream, guard, code, "本次请求处理失败，请稍后再试。");
            return;
        }
        switch (result.kind()) {
            case COMPLETE -> {
                boolean saved = processing.finish(accepted, result.status(), result.text(),
                        result.conclusion(), null);
                if (saved) {
                    contextStore.evict(accepted.sessionId());
                    stream.conclude(result.conclusion());
                } else {
                    log.debug("Stale callback ignored: sessionId={}, messageId={}",
                            accepted.sessionId(), accepted.messageId());
                }
                guard.complete();
            }
            case WAIT -> {
                boolean saved = processing.finish(accepted, result.status(), result.text(), null, null);
                if (saved) {
                    contextStore.save(new SessionContext(SessionContext.CURRENT_VERSION,
                            accepted.sessionId(), accepted.userId(), accepted.round(), accepted.messageId(),
                            capability.name(), result.status().name()));
                    stream.awaitUser(accepted.sessionId(), result.text());
                } else {
                    log.debug("Stale callback ignored: sessionId={}, messageId={}",
                            accepted.sessionId(), accepted.messageId());
                }
                guard.complete();
            }
            case FAIL -> failAccepted(accepted, stream, guard,
                    result.error() == null ? ErrorCode.INTERNAL_ERROR : result.error(), result.text());
            case REROUTE -> {
                guard.complete();
                if (!continuation) {
                    log.warn("Session request rejected: sessionId={}, reasonCode=REROUTE_WITHOUT_WAIT",
                            accepted.sessionId());
                    failAccepted(accepted, stream, guard, ErrorCode.INTERNAL_ERROR, "服务内部错误，请稍后再试。");
                    return;
                }
                boolean saved = processing.finish(accepted, SessionStatus.COMPLETED_UNFIXED, result.text(),
                        new ConclusionDto("UNFIXED_MANUAL_GUIDE", result.text(), null, null, null, null), null);
                if (saved) {
                    contextStore.evict(accepted.sessionId());
                    Accepted next = processing.accept(user, accepted.sessionId(), accepted.text(), null);
                    process(user, next, stream);
                }
            }
        }
    }

    /**
     * 失败统一收尾：以 FAILED_REQUEST 结清本轮（成功则清除会话上下文），
     * 无论落库与否都向客户端推 error 事件并结束守护，不假成功
     *
     * @param accepted 已接纳的本轮消息
     * @param stream   SSE 事件流
     * @param guard    本轮处理守护
     * @param code     失败错误码
     * @param visible  对用户可见的失败文案（空则用默认兜底文案）
     */
    private void failAccepted(Accepted accepted, SseEventStream stream, Guard guard,
                              ErrorCode code, String visible) {
        String text = visible == null || visible.isBlank() ? "本次请求处理失败，请稍后再试。" : visible;
        boolean saved = processing.finish(accepted, SessionStatus.FAILED_REQUEST, text, null, code);
        if (saved) {
            contextStore.evict(accepted.sessionId());
        } else {
            log.debug("Stale callback ignored: sessionId={}, messageId={}",
                    accepted.sessionId(), accepted.messageId());
        }
        stream.error(code.name(), text, accepted.sessionId());
        guard.complete();
    }

    // ========== 处理守护：owner 租约 + 固定截止 ==========

    /**
     * 单轮处理守护：持有 owner 租约并按固定间隔续期至持久化结束；失租约立即停发后续 token；
     * 到达固定处理截止时主动结清（时钟偏差则 1 秒后重试）；complete 幂等释放全部资源
     */
    private final class Guard {
        private final Accepted accepted;
        private final SseEventStream stream;
        private final SessionLeaseService.Lease lease;
        private final AtomicBoolean done = new AtomicBoolean();
        private final AtomicBoolean leaseLost = new AtomicBoolean();
        private final ScheduledFuture<?> renewTask;
        private ScheduledFuture<?> deadlineTask;

        Guard(Accepted accepted, SseEventStream stream) {
            this.accepted = accepted;
            this.stream = stream;
            this.lease = leases.acquire(accepted.sessionId(), accepted.messageId());
            if (lease == null) {
                // The database pointer stays authoritative; a stale lease only disables renewals.
                leaseLost.set(true);
                log.warn("Session lease lost: sessionId={}, messageId={}, reasonCode=ACQUIRE_FAILED",
                        accepted.sessionId(), accepted.messageId());
                renewTask = null;
            } else {
                renewTask = scheduler.scheduleWithFixedDelay(this::renewSafely,
                        properties.sessionRenewSeconds(), properties.sessionRenewSeconds(),
                        TimeUnit.SECONDS);
            }
            scheduleExpiry();
        }

        /**
         * token 下沉消费者：仅在本轮未结束且租约未丢失时推流，失租约后静默丢弃后续 token
         *
         * @return 供能力处理器推送流式 token 的消费者
         */
        Consumer<String> sink() {
            return token -> {
                if (!done.get() && !leaseLost.get()) {
                    stream.send(SseEvent.token(token));
                }
            };
        }

        /** 结束本轮守护（幂等）：取消续期与截止任务，租约仍有效则释放 */
        void complete() {
            if (done.compareAndSet(false, true)) {
                if (renewTask != null) {
                    renewTask.cancel(false);
                }
                if (deadlineTask != null) {
                    deadlineTask.cancel(false);
                }
                if (lease != null && !leaseLost.get()) {
                    leases.release(lease);
                }
            }
        }

        /** 租约续期：续期失败或异常均标记失租约并停发 token，DB 指针仍为权威 */
        private void renewSafely() {
            if (done.get() || lease == null) {
                return;
            }
            try (var ignored = LogContextUtils.install(accepted.logContext())) {
                if (!leases.renew(lease)) {
                    leaseLost.set(true);
                    log.warn("Session lease lost: sessionId={}, messageId={}, reasonCode=RENEW_FAILED",
                            accepted.sessionId(), accepted.messageId());
                }
            } catch (RuntimeException e) {
                log.warn("Session lease lost: sessionId={}, messageId={}, reasonCode=RENEW_ERROR",
                        accepted.sessionId(), accepted.messageId());
            }
        }

        /** 按持久化的处理截止定时触发超时结清（最小 1 秒延迟） */
        private void scheduleExpiry() {
            long delayMs = Math.max(1000L,
                    Duration.between(LocalDateTime.now(), accepted.deadline()).toMillis());
            deadlineTask = scheduler.schedule(this::expireSafely, delayMs, TimeUnit.MILLISECONDS);
        }

        /**
         * 到期结清：成功则推 REQUEST_TIMEOUT 并结束守护；DB 时间未过截止（时钟偏差）
         * 或结清异常时 1 秒后重试，保证最终一定结清
         */
        private void expireSafely() {
            if (done.get()) {
                return;
            }
            try (var ignored = LogContextUtils.install(accepted.logContext())) {
                if (processing.expire(accepted.sessionId(), accepted.messageId())) {
                    stream.error(ErrorCode.REQUEST_TIMEOUT.name(), "本次处理已超时，请重新提问。",
                            accepted.sessionId());
                    complete();
                } else if (!done.get()) {
                    // Database time has not passed the deadline yet (clock skew); retry shortly.
                    deadlineTask = scheduler.schedule(this::expireSafely, 1, TimeUnit.SECONDS);
                }
            } catch (RuntimeException e) {
                log.error("Session finalization failed: sessionId={}, messageId={}, errorCode=REQUEST_TIMEOUT",
                        accepted.sessionId(), accepted.messageId(), LogSanitizer.diagnostic(e));
                deadlineTask = scheduler.schedule(this::expireSafely, 1, TimeUnit.SECONDS);
            }
        }
    }

    // ========== 查询辅助 ==========

    /**
     * 会话列表摘要：优先取结论文本，无结论回退首条用户消息
     *
     * @param session 会话实体
     * @return 列表预览文案（可能为 null）
     */
    private String previewOf(RepairSession session) {
        if (session.getConclusion() != null && !session.getConclusion().isBlank()) {
            return session.getConclusion();
        }
        ChatMessage first = messageMapper.selectOneByQuery(QueryWrapper.create()
                .where("session_id = ?", session.getId())
                .and("role = ?", "USER")
                .orderBy("id", true).limit(1));
        return first == null ? null : first.getContent();
    }

    /**
     * 详情页当前回复文案：等待态取最近一条助手消息，执行/验证中给固定提示，其余取结论
     *
     * @param session 会话实体
     * @param status  会话当前状态
     * @return 回复文案（可能为 null）
     */
    private String replyFor(RepairSession session, SessionStatus status) {
        return switch (status) {
            case CLARIFYING, DEVICE_CONFIRMING, CONFIRMING_REPAIR, AWAITING_LOCATION ->
                    lastAssistantMessage(session.getId());
            case REPAIRING, VERIFYING -> "正在执行操作，请稍后查询结果。";
            default -> session.getConclusion();
        };
    }

    /**
     * 取会话最近一条助手消息
     *
     * @param sessionId 会话 ID
     * @return 消息内容（无则 null）
     */
    private String lastAssistantMessage(Long sessionId) {
        ChatMessage msg = messageMapper.selectOneByQuery(QueryWrapper.create()
                .where("session_id = ?", sessionId)
                .and("role = ?", "ASSISTANT")
                .orderBy("id", false).limit(1));
        return msg == null ? null : msg.getContent();
    }

    /**
     * 组装终态结论：诊断快照取同阶段最后一条（conclusion_extra 中的覆盖项优先），
     * 手动步骤与售后网点从 conclusion_extra 反序列化；无结论类型时返回 null
     *
     * @param session 会话实体
     * @return 结论 DTO（无结论时 null）
     */
    private ConclusionDto buildConclusion(RepairSession session) {
        if (session.getConclusionType() == null) {
            return null;
        }
        List<DiagnosticSnapshot> snapshots = snapshotMapper.selectListByQuery(QueryWrapper.create()
                .where("session_id = ?", session.getId()));
        Map<String, Object> pre = snapshots.stream()
                .filter(s -> SnapshotPhase.PRE.name().equals(s.getPhase()))
                .reduce((a, b) -> b).map(s -> fromJson(s.getPayload())).orElse(null);
        Map<String, Object> post = snapshots.stream()
                .filter(s -> SnapshotPhase.POST.name().equals(s.getPhase()))
                .reduce((a, b) -> b).map(s -> fromJson(s.getPayload())).orElse(null);
        Map<String, Object> extra = session.getConclusionExtra() == null
                ? Map.of() : fromJson(session.getConclusionExtra());
        return new ConclusionDto(
                session.getConclusionType(),
                session.getConclusion(),
                extra.get("preDiagnostics") instanceof Map<?, ?> typed
                        ? typedMap(typed) : pre,
                post,
                extra.get("manualSteps") instanceof List<?> typed
                        ? typedList(typed, String.class) : null,
                extra.get("afterSales") instanceof List<?> typed
                        ? typedList(typed, com.chh.autosense.core.aftersales.AfterSalesLocation.class) : null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> typedMap(Object value) {
        return objectMapper.convertValue(value, Map.class);
    }

    private <T> List<T> typedList(Object value, Class<T> elementType) {
        return objectMapper.convertValue(value,
                objectMapper.getTypeFactory().constructCollectionType(List.class, elementType));
    }

    /** 反序列化 JSON 对象为 Map；解析失败回退空 Map（结论附加字段不影响主流程） */
    private Map<String, Object> fromJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }
}
