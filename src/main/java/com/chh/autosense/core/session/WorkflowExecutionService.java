package com.chh.autosense.core.session;

import com.chh.autosense.config.GraphProperties;
import com.chh.autosense.core.security.AuthUser;
import com.chh.autosense.core.session.memory.ConversationHistoryService;
import com.chh.autosense.domain.dto.*;
import com.chh.autosense.domain.enums.WorkflowStatus;
import com.chh.autosense.graph.checkpoint.MyBatisCheckpointSaver;
import com.chh.autosense.graph.node.GraphUpdates;
import com.chh.autosense.graph.state.*;
import com.chh.autosense.mapper.WorkflowExecutionMapper;
import com.chh.autosense.service.knowledge.UserAiServiceCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.*;
import org.bsc.langgraph4j.checkpoint.*;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.chh.autosense.graph.state.AssistantState.*;

/** Authenticated admission and graph resources only; capability ordering lives in MainGraph. */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowExecutionService {
    private final CompiledGraph<AssistantState> graph;
    private final BaseCheckpointSaver saver;
    private final WorkflowPersistenceService persistence;
    private final WorkflowClaimService claims;
    private final WorkflowApprovalService approvals;
    private final WorkflowRecoveryService recovery;
    private final ConversationQueryService conversations;
    private final ConversationHistoryService history;
    private final WorkflowExecutionMapper workflows;
    private final SessionLeaseService leases;
    private final GraphProperties properties;
    private final UserAiServiceCache cache;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "workflow-lease"); thread.setDaemon(true); return thread;
    });

    @jakarta.annotation.PreDestroy
    public void closeScheduler() { scheduler.shutdownNow(); }

    public Run create(AuthUser user, Long sessionId, String text) throws Exception {
        var accepted = persistence.admit(user, sessionId, text);
        var workflow = workflows.selectOneById(accepted.requestId());
        var claim = claims.acquire(accepted.requestId(), user.userId(), workflow.getVersion());
        try {
            var data = new LinkedHashMap<>(AssistantState.initial(new RequestContext(accepted.requestId(), accepted.sessionId(), user.userId(), text)));
            var initial = new AssistantState(data); var w = initial.workflow();
            data.put(WORKFLOW, new WorkflowContext(w.status(), 0, w.progress(), workflow.getVersion() + 1, "", "", "", "", com.chh.autosense.graph.checkpoint.AssistantStateSerializer.SCHEMA_VERSION, com.chh.autosense.graph.checkpoint.AssistantStateSerializer.GRAPH_VERSION, workflow.getLastEventSequence(), claim.fence()));
            data.put(AUDIT, new AuditContext(accepted.requestId(), accepted.reportId(), accepted.messageId(), accepted.round(), List.of(), List.of()));
            var messages = new ArrayList<>(history.snapshot(user.userId(), accepted.sessionId(), accepted.messageId()).messages().stream()
                    .map(m -> new Message(m.id(), m.role(), m.content())).toList());
            messages.add(new Message(accepted.messageId(), "USER", text)); data.put(MESSAGES, messages);
            var config = saver.put(config(claim), Checkpoint.builder().state(data).nodeId(StateGraph.START).nextNodeId("IntentPlanner").build());
            if (properties.mode().equals("real")) cache.getOrCreate(user);
            return new Run(graph.stream(GraphInput.resume(), config), claim, accepted.sessionId(), accepted.messageId());
        } catch (Exception e) { try { recovery.suspendFailedExecution(claim); } catch (Exception recoveryFailure) { e.addSuppressed(recoveryFailure); }
            finally { claims.release(claim); }
            throw e; }
    }

    public Run message(AuthUser user, long sessionId, MessageRequest request) throws Exception {
        var session = conversations.owned(user, sessionId);
        if (request.confirmRepair() != null) {
            if (session.getActiveWorkflowRequestId() == null) throw WorkflowClaimService.conflict("APPROVAL_SCOPE_MISMATCH");
            var view = conversations.workflow(user, sessionId, session.getActiveWorkflowRequestId());
            if (view.status() != WorkflowStatus.WAITING_APPROVAL || view.approval() == null
                    || view.currentStep() >= view.steps().size() || view.steps().get(view.currentStep()).type() != com.chh.autosense.domain.enums.PlanStepType.DEVICE_CONTROL)
                throw WorkflowClaimService.conflict("APPROVAL_SCOPE_MISMATCH");
            return approval(user, sessionId, view.requestId(), new WorkflowApprovalRequest(view.approval().stepId(), view.approval().approvalId(), request.confirmRepair(), view.version()));
        }
        if (session.getActiveWorkflowRequestId() != null || request.inputRequestId() != null) {
            var input = persistence.acceptInput(user, sessionId, request.inputRequestId(), request.expectedVersion(), request.content());
            return input.execute() ? continuation(user, sessionId, input.requestId(), input.version(), false) : snapshot(input.requestId());
        }
        return create(user, sessionId, request.content());
    }

    public Run approval(AuthUser user, long sessionId, String requestId, WorkflowApprovalRequest request) throws Exception {
        var decision = approvals.decide(user, sessionId, requestId, request.stepId(), request.approvalId(), request.approved(), request.expectedVersion());
        return decision.execute() ? continuation(user, sessionId, requestId, decision.version(), false) : snapshot(requestId);
    }
    public Run resume(AuthUser user, long sessionId, String requestId, long version) throws Exception {
        var view = conversations.workflow(user, sessionId, requestId);
        if (view.status().terminal()) return snapshot(requestId);
        return continuation(user, sessionId, requestId, version, true);
    }
    public Run cancel(AuthUser user, long sessionId, String requestId, long version) throws Exception {
        var view = conversations.workflow(user, sessionId, requestId);
        if (view.status().terminal()) return snapshot(requestId);
        return runRestored(user, sessionId, recovery.cancel(user, sessionId, requestId, version));
    }

    private Run continuation(AuthUser user, long sessionId, String requestId, long version, boolean explicitResume) throws Exception {
        var restored = recovery.restore(user, sessionId, requestId, version, explicitResume);
        return runRestored(user, sessionId, restored);
    }

    private Run runRestored(AuthUser user, long sessionId, WorkflowRecoveryService.Restored restored) throws Exception {
        try {
            var config = graph.updateState(config(restored.claim()), restored.state().data(), restored.asNode());
            if (properties.mode().equals("real")) cache.getOrCreate(user);
            return new Run(graph.stream(GraphInput.resume(), config), restored.claim(), sessionId,
                    workflows.selectOneById(restored.claim().requestId()).getLatestInputMessageId());
        } catch (Exception e) { try { recovery.suspendFailedExecution(restored.claim()); } catch (Exception recoveryFailure) { e.addSuppressed(recoveryFailure); }
            finally { claims.release(restored.claim()); }
            throw e; }
    }

    private Run snapshot(String requestId) {
        var state = graph.getState(RunnableConfig.builder().threadId(requestId).build()).state();
        var row = workflows.selectOneById(requestId);
        var status = WorkflowStatus.valueOf(row.getStatus());
        var output = state.output(); var data = new LinkedHashMap<>(output.data());
        data.put("version", row.getVersion()); data.put("status", row.getStatus());
        data.put("sequence", row.getLastEventSequence()); data.put("eventId", requestId + ":" + row.getLastEventSequence());
        data.put("requestId", requestId); data.put("conversationId", row.getSessionId());
        var progress = state.workflow().progress();
        data.put("progress", Map.of("total", progress.total(), "completed", progress.completed(), "skipped", progress.skipped(), "notExecuted", progress.notExecuted()));
        data.put("payload", Map.of("canResume", status == WorkflowStatus.WAITING_RESUME, "simulated", properties.mode().equals("stub")));
        String type = status.terminal() ? status == WorkflowStatus.COMPLETED ? "CONCLUSION" : "ERROR" : "AWAITING";
        state = GraphUpdates.apply(state, Map.of(OUTPUT, new OutputContext(type, properties.mode().equals("stub") ? "STUB_CURRENT_STATE" : "CURRENT_STATE",
                status == WorkflowStatus.WAITING_RESUME ? "处理已暂停，请明确选择继续。" : output.message(), data)));
        return new Run(List.of(NodeOutput.of("PersistedSnapshot", state)), null, state.request().conversationId(), 0);
    }
    private RunnableConfig config(WorkflowClaimService.Claim claim) {
        return RunnableConfig.builder().threadId(claim.requestId()).build().updateMetadata(Map.of(MyBatisCheckpointSaver.EXECUTION_FENCE, claim.fence()));
    }

    public final class Run implements AutoCloseable {
        private final Iterable<NodeOutput<AssistantState>> stream;
        private final WorkflowClaimService.Claim claim;
        private final SessionLeaseService.Lease lease;
        private final ScheduledFuture<?> renewal;
        private final AtomicBoolean closed = new AtomicBoolean();
        private Run(Iterable<NodeOutput<AssistantState>> stream, WorkflowClaimService.Claim claim, long sessionId, long messageId) {
            this.stream = stream; this.claim = claim;
            SessionLeaseService.Lease acquired = null;
            if (claim != null) {
                try { acquired = leases.acquire(sessionId, messageId); }
                catch (RuntimeException e) { log.warn("Workflow auxiliary lease unavailable: reasonCode=REDIS_UNAVAILABLE"); }
            }
            lease = acquired;
            renewal = lease == null ? null : scheduler.scheduleAtFixedRate(() -> {
                try { leases.renew(lease); } catch (RuntimeException e) { log.warn("Workflow auxiliary lease renewal failed: reasonCode=REDIS_UNAVAILABLE"); }
            }, 10, 10, TimeUnit.SECONDS);
        }
        public Iterable<NodeOutput<AssistantState>> stream() {
            return () -> {
                var delegate = stream.iterator();
                return new Iterator<>() {
                    public boolean hasNext() {
                        boolean next = delegate.hasNext();
                        if (!next) close(); // Release before publishing a durable human-wait notification.
                        return next;
                    }
                    public NodeOutput<AssistantState> next() { return delegate.next(); }
                };
            };
        }
        public void failed() { if (claim != null) recovery.suspendFailedExecution(claim); }
        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            if (renewal != null) renewal.cancel(false);
            try { if (claim != null) claims.release(claim); }
            finally {
                if (lease != null) try { leases.release(lease); } catch (RuntimeException e) { log.warn("Workflow auxiliary lease release failed: reasonCode=REDIS_UNAVAILABLE"); }
            }
        }
    }
}
