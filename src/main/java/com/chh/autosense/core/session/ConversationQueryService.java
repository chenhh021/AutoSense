package com.chh.autosense.core.session;

import com.chh.autosense.core.security.AuthUser;
import com.chh.autosense.domain.dto.*;
import com.chh.autosense.domain.entity.*;
import com.chh.autosense.domain.enums.*;
import com.chh.autosense.domain.vo.*;
import com.chh.autosense.exception.*;
import com.chh.autosense.graph.state.*;
import com.chh.autosense.mapper.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.time.ZoneOffset;
import java.util.*;

/** History reads never expire, resume or execute a workflow. */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConversationQueryService {
    private final RepairSessionMapper sessions;
    private final ChatMessageMapper messages;
    private final WorkflowExecutionMapper workflows;
    private final WorkflowStepMapper steps;
    private final WorkflowApprovalMapper approvals;
    private final WorkflowCheckpointMapper checkpoints;
    private final CommandExecutionMapper commands;
    private final RepairActionLogMapper audit;
    private final ProblemReportMapper reports;
    private final DiagnosticSnapshotMapper snapshots;
    private final ObjectMapper json;
    private final DeviceMapper devices;

    public RepairSession owned(AuthUser user, long sessionId) {
        if (user == null) throw new ApiException(ErrorCode.UNAUTHORIZED, "请先登录");
        var session = sessions.selectOneById(sessionId);
        if (session == null) throw new ApiException(ErrorCode.SESSION_NOT_FOUND, "会话不存在");
        if (!session.getUserId().equals(user.userId())) throw new ApiException(ErrorCode.FORBIDDEN, "无权访问此会话");
        return session;
    }

    @Transactional(readOnly = true)
    public SessionResponse get(AuthUser user, long sessionId) {
        var session = owned(user, sessionId);
        var history = workflows.forConversation(sessionId);
        WorkflowView workflow = history.isEmpty() ? null : view(history.getFirst());
        boolean waiting = workflow == null ? SessionStatus.valueOf(session.getStatus()).isAwaitingUser()
                : Set.of(WorkflowStatus.WAITING_INPUT, WorkflowStatus.WAITING_APPROVAL, WorkflowStatus.WAITING_RESUME).contains(workflow.status());
        var last = messages.selectOneByQuery(QueryWrapper.create().where("session_id = ?", sessionId)
                .and("role = ?", "ASSISTANT").orderBy("id", false).limit(1));
        String reply = waiting && last != null ? last.getContent() : session.getConclusion();
        return new SessionResponse(sessionId, workflow == null ? session.getStatus() : WorkflowPersistenceService.legacyStatus(workflow.status()),
                reply, waiting, workflow != null && workflow.status().terminal()
                ? new ConclusionDto(workflow.status() == WorkflowStatus.COMPLETED ? "ANSWERED" : "FAILED", reply, null, null, null, null)
                : legacyConclusion(session), workflow);
    }

    @Transactional(readOnly = true)
    public WorkflowView workflow(AuthUser user, long sessionId, String requestId) {
        owned(user, sessionId);
        var workflow = workflows.selectOneById(requestId);
        if (workflow == null) throw new ApiException(ErrorCode.SESSION_NOT_FOUND, "工作流不存在");
        if (workflow.getSessionId() != sessionId || !workflow.getUserId().equals(user.userId())) throw new ApiException(ErrorCode.FORBIDDEN, "无权访问此工作流");
        return view(workflow);
    }

    private WorkflowView view(WorkflowExecution workflow) {
        var rows = steps.forWorkflow(workflow.getRequestId());
        var commandRows = commands.forWorkflow(workflow.getRequestId());
        var views = rows.stream().map(step -> {
            var command = commandRows.stream().filter(c -> c.getStepId().equals(step.getStepId())).findFirst().orElse(null);
            return new WorkflowView.StepView(step.getStepId(), PlanStepType.valueOf(step.getType()), ExecutionPlan.StepStatus.valueOf(step.getStatus()),
                    object(step.getResultJson()), step.getFailureCode(), step.getCommandExecutionId(),
                    ExecutionPlan.Certainty.valueOf(command == null ? step.getCertainty() : command.getCertainty()), step.getRetriesUsed());
        }).toList();
        var progress = new AssistantState.Progress(rows.size(), count(rows, "COMPLETED"), count(rows, "SKIPPED"), count(rows, "NOT_EXECUTED"));
        WorkflowView.ApprovalView approval = null;
        if (workflow.getCurrentStepIndex() < rows.size()) {
            var current = approvals.current(workflow.getRequestId(), rows.get(workflow.getCurrentStepIndex()).getStepId());
            if (current != null && current.getStatus().equals("PENDING")) {
                var parameters = new LinkedHashMap<>(object(rows.get(workflow.getCurrentStepIndex()).getInputJson()));
                try {
                    Object refs = json.readValue(current.getDeviceRefs(), Object.class);
                    parameters.put("deviceRefs", refs);
                    var ids = refs instanceof List<?> list ? list : List.of(refs);
                    var targets = devices.selectMine(workflow.getUserId()).stream()
                            .filter(d -> ids.stream().anyMatch(id -> id instanceof Number n && n.longValue() == d.getId()))
                            .toList();
                    // Display identity comes from owned bindings, never from planner-supplied text.
                    parameters.keySet().removeAll(Set.of("name", "sn", "deviceType", "model", "deviceNames"));
                    if (targets.size() == 1) {
                        var target = targets.getFirst();
                        parameters.put("name", target.getName());
                        parameters.put("sn", target.getSn());
                        parameters.put("deviceType", target.getDeviceTypeCode());
                        parameters.put("model", target.getDeviceModelCode());
                    }
                    var names = targets.stream().map(Device::getName).filter(Objects::nonNull).toList();
                    if (!names.isEmpty()) parameters.put("deviceNames", names);
                }
                catch (Exception e) { throw new IllegalStateException("Stored approval scope is invalid", e); }
                approval = new WorkflowView.ApprovalView(current.getApprovalId(), current.getStepId(),
                        "请确认是否执行此设备步骤。", current.getExpiresAt().toInstant(ZoneOffset.UTC), current.getOperationKind(), parameters);
            }
        }
        var status = WorkflowStatus.valueOf(workflow.getStatus());
        return new WorkflowView(workflow.getRequestId(), workflow.getSessionId(), status, workflow.getCurrentStepIndex(), progress,
                workflow.getVersion(), status == WorkflowStatus.WAITING_RESUME && Integer.valueOf(com.chh.autosense.graph.checkpoint.AssistantStateSerializer.SCHEMA_VERSION).equals(workflow.getSchemaVersion())
                        && com.chh.autosense.graph.checkpoint.AssistantStateSerializer.GRAPH_VERSION.equals(workflow.getGraphVersion()) && checkpoints.latest(workflow.getRequestId()) != null,
                views, approval, workflow.getFailureCode(), workflow.getInputRequestId(), workflow.getPrompt());
    }
    private int count(List<WorkflowStep> rows, String status) { return (int) rows.stream().filter(s -> s.getStatus().equals(status)).count(); }

    @Transactional(readOnly = true)
    public List<SessionListItemView> list(AuthUser user) {
        return sessions.selectListByQuery(QueryWrapper.create().where("user_id = ?", user.userId()).orderBy("updated_at", false)).stream().map(session -> {
            String preview = session.getConclusion();
            if (preview == null || preview.isBlank()) {
                var first = messages.selectOneByQuery(QueryWrapper.create().where("session_id = ?", session.getId()).and("role = ?", "USER").orderBy("id", true).limit(1));
                preview = first == null ? null : first.getContent();
            }
            var latest = workflows.latestForConversation(session.getId());
            String status = latest == null ? session.getStatus()
                    : WorkflowPersistenceService.legacyStatus(WorkflowStatus.valueOf(latest.getStatus()));
            return new SessionListItemView(session.getId(), status, preview, session.getCreatedAt(), session.getUpdatedAt());
        }).toList();
    }

    @Transactional(readOnly = true)
    public List<ChatMessage> messages(AuthUser user, long sessionId) {
        owned(user, sessionId);
        return messages.selectListByQuery(QueryWrapper.create().where("session_id = ?", sessionId).orderBy("id", true));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void delete(AuthUser user, long sessionId) {
        owned(user, sessionId);
        // Match result/checkpoint writers: workflow rows first, conversation row second.
        // Holding these locks prevents a continuation from acquiring a new execution claim.
        var history = workflows.forConversation(sessionId);
        for (var workflow : history) workflows.lock(workflow.getRequestId());
        var session = sessions.lockById(sessionId);
        if (session == null) throw new ApiException(ErrorCode.SESSION_NOT_FOUND, "会话不存在");
        if (session.getProcessingMessageId() != null)
            throw new ApiException(ErrorCode.WORKFLOW_BUSY, "旧版请求尚未完成，暂时无法删除对话");
        var current = workflows.forConversation(sessionId);
        if (!current.stream().map(WorkflowExecution::getRequestId).toList()
                .equals(history.stream().map(WorkflowExecution::getRequestId).toList()))
            throw new ApiException(ErrorCode.WORKFLOW_BUSY, "对话刚接收了新请求，请稍后重试删除");
        for (var workflow : history) {
            // Re-read with a locking/current read, not the transaction's earlier snapshot.
            var locked = workflows.lock(workflow.getRequestId());
            if (locked != null && locked.getLeaseOwner() != null && locked.getLeaseUntil() != null
                    && locked.getLeaseUntil().isAfter(workflows.databaseNow()))
                throw new ApiException(ErrorCode.WORKFLOW_BUSY, "对话仍在执行，请等待当前请求结束后再删除");
            if (commands.forWorkflow(workflow.getRequestId()).stream().anyMatch(c ->
                    Set.of("UNKNOWN", "IN_FLIGHT").contains(c.getCertainty())
                            || Set.of("UNKNOWN", "IN_FLIGHT").contains(c.getStatus())))
                throw new ApiException(ErrorCode.WORKFLOW_BUSY, "设备命令仍在执行或结果尚未确定，暂时无法删除对话");
        }
        for (var workflow : history) {
            String id = workflow.getRequestId();
            checkpoints.deleteByQuery(QueryWrapper.create().where("thread_id = ?", id));
            approvals.deleteByQuery(QueryWrapper.create().where("request_id = ?", id));
            commands.deleteByQuery(QueryWrapper.create().where("request_id = ?", id));
            steps.deleteByQuery(QueryWrapper.create().where("request_id = ?", id));
        }
        audit.deleteByQuery(QueryWrapper.create().where("session_id = ?", sessionId));
        messages.deleteByQuery(QueryWrapper.create().where("session_id = ?", sessionId));
        snapshots.deleteByQuery(QueryWrapper.create().where("session_id = ?", sessionId));
        reports.deleteByQuery(QueryWrapper.create().where("session_id = ?", sessionId));
        workflows.deleteByQuery(QueryWrapper.create().where("session_id = ?", sessionId));
        sessions.deleteById(sessionId);
        log.info("Conversation deleted: conversationId={}, userId={}, workflowCount={}", sessionId, user.userId(), history.size());
    }

    private ConclusionDto legacyConclusion(RepairSession session) {
        if (session.getConclusionType() == null || !SessionStatus.valueOf(session.getStatus()).isTerminal()) return null;
        var rows = snapshots.selectListByQuery(QueryWrapper.create().where("session_id = ?", session.getId()).orderBy("id", true));
        Map<String, Object> pre = null, post = null;
        for (var row : rows) { if (row.getPhase().equals("PRE")) pre = object(row.getPayload()); if (row.getPhase().equals("POST")) post = object(row.getPayload()); }
        var extra = object(session.getConclusionExtra());
        if (extra.get("preDiagnostics") instanceof Map<?, ?> value) pre = json.convertValue(value, new TypeReference<>() { });
        return new ConclusionDto(session.getConclusionType(), session.getConclusion(), pre, post,
                extra.get("manualSteps") instanceof List<?> value ? json.convertValue(value, new TypeReference<List<String>>() { }) : null,
                extra.get("afterSales") instanceof List<?> value ? json.convertValue(value, new TypeReference<List<com.chh.autosense.core.aftersales.AfterSalesLocation>>() { }) : null);
    }
    private Map<String, Object> object(String value) {
        if (value == null) return Map.of();
        try { return json.readValue(value, new TypeReference<>() { }); }
        catch (Exception e) { throw new IllegalStateException("Stored conversation data is invalid", e); }
    }
}
