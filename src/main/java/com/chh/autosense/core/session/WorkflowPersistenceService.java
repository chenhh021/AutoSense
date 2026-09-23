package com.chh.autosense.core.session;

import com.chh.autosense.config.GraphProperties;
import com.chh.autosense.core.security.AuthUser;
import com.chh.autosense.domain.entity.*;
import com.chh.autosense.domain.enums.*;
import com.chh.autosense.exception.*;
import com.chh.autosense.graph.node.GraphUpdates;
import com.chh.autosense.graph.node.PlanValidator;
import com.chh.autosense.graph.state.*;
import com.chh.autosense.mapper.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import static com.chh.autosense.graph.state.AssistantState.*;

/** Short transactions for admission and projections. All external calls remain outside this service. */
@Service
public class WorkflowPersistenceService {
    private final RepairSessionMapper sessions;
    private final ProblemReportMapper reports;
    private final ChatMessageMapper messages;
    private final WorkflowExecutionMapper workflows;
    private final WorkflowStepMapper steps;
    private final WorkflowClaimService claims;
    private final WorkflowAuditService audit;
    private final GraphProperties properties;
    private final ObjectMapper json;
    private final WorkflowApprovalMapper approvals;
    public WorkflowPersistenceService(RepairSessionMapper sessions, ProblemReportMapper reports, ChatMessageMapper messages,
            WorkflowExecutionMapper workflows, WorkflowStepMapper steps, WorkflowClaimService claims,
            WorkflowAuditService audit, GraphProperties properties, ObjectMapper json, WorkflowApprovalMapper approvals) {
        this.sessions = sessions; this.reports = reports; this.messages = messages; this.workflows = workflows;
        this.steps = steps; this.claims = claims; this.audit = audit; this.properties = properties; this.json = json; this.approvals = approvals;
    }

    @Transactional
    public AcceptedWorkflow admit(AuthUser user, Long sessionId, String text) {
        if (user == null || user.userId() == null) throw new ApiException(ErrorCode.UNAUTHORIZED, "请先登录");
        if (text == null || text.isBlank() || text.length() > 16000) throw new ApiException(ErrorCode.BAD_REQUEST, "请输入有效的问题");
        RepairSession session;
        if (sessionId == null) {
            session = new RepairSession(); session.setUserId(user.userId()); session.setStatus("CREATED"); sessions.insert(session);
        } else {
            session = sessions.lockById(sessionId);
            if (session == null) throw new ApiException(ErrorCode.SESSION_NOT_FOUND, "会话不存在");
            if (!session.getUserId().equals(user.userId())) throw new ApiException(ErrorCode.FORBIDDEN, "无权访问此会话");
            if (session.getActiveWorkflowRequestId() != null) {
                var active = workflows.selectOneById(session.getActiveWorkflowRequestId());
                if (active == null || !WorkflowStatus.valueOf(active.getStatus()).terminal()) throw WorkflowClaimService.conflict("WORKFLOW_BUSY");
            }
            if (session.getProcessingMessageId() != null) throw WorkflowClaimService.conflict("WORKFLOW_BUSY");
        }
        // RequestLogFilter generates this identity; client headers are never used as a thread ID.
        String requestId = com.chh.autosense.utils.LogContextUtils.snapshot().getOrDefault("requestId", UUID.randomUUID().toString());
        var previous = reports.latest(session.getId());
        var report = new ProblemReport(); report.setSessionId(session.getId()); report.setRound(previous == null ? 1 : previous.getRound() + 1);
        report.setRawText(text); reports.insert(report);
        var message = message(session.getId(), requestId, null, "input:original", "USER", text);
        var workflow = new WorkflowExecution(); workflow.setRequestId(requestId); workflow.setUserId(user.userId());
        workflow.setSessionId(session.getId()); workflow.setReportId(report.getId()); workflow.setOriginMessageId(message.getId());
        workflow.setLatestInputMessageId(message.getId()); workflow.setGraphVersion(com.chh.autosense.graph.checkpoint.AssistantStateSerializer.GRAPH_VERSION); workflow.setSchemaVersion(com.chh.autosense.graph.checkpoint.AssistantStateSerializer.SCHEMA_VERSION);
        workflow.setStatus("CREATED"); workflow.setCurrentStepIndex(0); workflow.setVersion(0L); workflow.setFence(0L);
        workflow.setLastEventSequence(0L); workflows.insertSelective(workflow);
        session.setActiveWorkflowRequestId(requestId); session.setStatus("DISPATCHING"); sessions.update(session);
        audit.append(workflow, "admitted", "WORKFLOW_ADMITTED", null, null, null, "RECORDED", "ACCEPTED", message.getId(), Map.of("schemaVersion", com.chh.autosense.graph.checkpoint.AssistantStateSerializer.SCHEMA_VERSION));
        return new AcceptedWorkflow(requestId, session.getId(), user.userId(), message.getId(), report.getId(), report.getRound(), text);
    }

    @Transactional
    public void commitResult(AssistantState state, ExecutionPlan.Result result) {
        var workflow = claims.requireFence(state.request().requestId(), state.workflow().executionFence());
        ensurePlan(workflow, state);
        saveResult(workflow, state.plan().step().stepId(), result);
    }

    public record InputAccepted(String requestId, long version, boolean execute) { }

    @Transactional
    public InputAccepted acceptInput(AuthUser user, long sessionId, String inputRequestId, Long expectedVersion, String text) {
        if (text == null || text.isBlank() || text.length() > 16000) throw new ApiException(ErrorCode.BAD_REQUEST, "请补充有效信息");
        var session = sessions.selectOneById(sessionId);
        if (session == null || !session.getUserId().equals(user.userId())) throw new ApiException(ErrorCode.FORBIDDEN, "无权访问此会话");
        if (session.getActiveWorkflowRequestId() == null) throw WorkflowClaimService.conflict("VERSION_CONFLICT");
        var workflow = claims.ownedLocked(session.getActiveWorkflowRequestId(), user.userId());
        com.chh.autosense.graph.checkpoint.AssistantStateSerializer.requireExecutable(workflow.getSchemaVersion(), workflow.getGraphVersion());
        boolean restart = workflow.getStatus().equals("WAITING_RESUME");
        if (inputRequestId == null && !restart) inputRequestId = workflow.getInputRequestId();
        if (inputRequestId == null || inputRequestId.isBlank() || !inputRequestId.equals(workflow.getInputRequestId()))
            throw WorkflowClaimService.conflict("VERSION_CONFLICT");
        String key = "input:" + inputRequestId;
        var existing = messages.byOutputKey(workflow.getRequestId(), key);
        if (existing != null) {
            if (!existing.getContent().equals(text)) throw WorkflowClaimService.conflict("VERSION_CONFLICT");
            return new InputAccepted(workflow.getRequestId(), workflow.getVersion(), false);
        }
        if (expectedVersion != null && expectedVersion.longValue() != workflow.getVersion()
                || restart && expectedVersion == null) throw WorkflowClaimService.conflict("VERSION_CONFLICT");
        if (!(workflow.getStatus().equals("WAITING_INPUT") || restart && "WAITING_INPUT".equals(workflow.getSuspendedStatus())))
            throw WorkflowClaimService.conflict("WORKFLOW_BUSY");
        var message = message(sessionId, workflow.getRequestId(), null, key, "USER", text);
        workflow.setLatestInputMessageId(message.getId()); workflows.update(workflow);
        audit.append(workflow, key, "WORKFLOW_INPUT_ACCEPTED", null, null, null, "RECORDED", "INPUT_ACCEPTED", message.getId(),
                Map.of("inputRequestId", inputRequestId));
        return new InputAccepted(workflow.getRequestId(), workflow.getVersion(), !restart);
    }

    @Transactional
    public void beginQuery(AssistantState state) {
        var workflow = claims.requireFence(state.request().requestId(), state.workflow().executionFence());
        var ref = state.control().approvalRef();
        var approval = ref == null ? null : approvals.lock(ref.approvalId());
        if (approval == null || !approval.getStatus().equals("APPROVED") || !approval.getScopeHash().equals(ref.scopeHash())
                || !approval.getScopeHash().equals(com.chh.autosense.graph.node.DeviceStepNodes.scope(state))
                || approval.getUserId() != state.request().userId() || !approval.getRequestId().equals(workflow.getRequestId())
                || !approval.getStepId().equals(state.plan().step().stepId()) || !approval.getExpiresAt().isAfter(workflows.databaseNow()))
            throw WorkflowClaimService.conflict("APPROVAL_SCOPE_MISMATCH");
        var step = steps.lock(workflow.getRequestId(), state.plan().step().stepId());
        if (step == null || !step.getType().equals("DEVICE_QUERY") || Set.of("COMPLETED", "FAILED", "SKIPPED").contains(step.getStatus()))
            throw WorkflowClaimService.conflict("INVALID_QUERY_ATTEMPT");
        String attempt = UUID.randomUUID().toString(); step.setActiveAttemptId(attempt); step.setRetriesUsed(state.retry().retriesUsed());
        step.setCertainty("IN_FLIGHT"); step.setStartedAt(workflows.databaseNow()); steps.update(step);
        audit.append(workflow, "attempt:" + attempt + ":started", "QUERY_ATTEMPT_STARTED", step.getStepId(), null, attempt,
                "RECORDED", "IN_FLIGHT", null, Map.of("stepType", "DEVICE_QUERY", "retriesUsed", state.retry().retriesUsed()));
    }

    @Transactional
    public Map<String, Object> project(AssistantState before, Map<String, Object> change) {
        if (change.isEmpty()) return change;
        var state = GraphUpdates.apply(before, change);
        var workflow = claims.requireFence(state.request().requestId(), state.workflow().executionFence());
        ensurePlan(workflow, state);
        state.plan().results().forEach((stepId, result) -> saveResult(workflow, stepId, result));
        workflow.setStatus(state.workflow().status().name()); workflow.setCurrentStepIndex(state.plan().currentStep());
        workflow.setFailureCode(state.workflow().failureCode()); workflow.setInputRequestId(state.workflow().inputRequestId());
        workflow.setPrompt(state.workflow().prompt()); workflow.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        if (state.plan().currentStep() < state.plan().executionPlan().steps().size() && !state.plan().executionPlan().hash().isBlank()) {
            var step = steps.lock(workflow.getRequestId(), state.plan().step().stepId());
            if (step != null && !state.plan().results().containsKey(step.getStepId())) {
                step.setInputJson(encode(state.plan().runtimeInputs())); step.setInputHash(PlanValidator.digest(state.plan().runtimeInputs()));
                step.setRetriesUsed(state.retry().retriesUsed()); step.setActiveAttemptId(state.retry().activeAttemptId());
                step.setStatus(switch (state.workflow().status()) {
                    case WAITING_APPROVAL -> "WAITING_APPROVAL"; case RETRYING -> "RETRYING"; default -> "RUNNING";
                }); steps.update(step);
            }
        }
        var delta = new LinkedHashMap<>(change);
        if (change.get(OUTPUT) instanceof OutputContext output && !output.type().isBlank()) {
            boolean initialized = change.get(DEVICE) instanceof DeviceContext context && context.initialized()
                    && !before.<DeviceContext>value(DEVICE).orElseThrow().initialized();
            String key = initialized ? "device-context:initialized" : "output:" + output.data().get("eventId");
            String stepId = (String) output.data().get("stepId");
            String body = visibleBody(output);
            String messageKey = switch (output.type()) {
                case "STEP_RESULT" -> "step:" + stepId + ":result";
                case "CONCLUSION" -> "workflow:conclusion";
                case "ERROR" -> "workflow:error";
                case "CONFIRM" -> "approval:" + state.control().approvalRef().approvalId();
                case "CLARIFY" -> "prompt:" + state.workflow().inputRequestId();
                default -> key;
            };
            ChatMessage saved = body.isBlank() ? null : message(workflow.getSessionId(), workflow.getRequestId(), stepId, messageKey, "ASSISTANT", body);
            var metadata = new LinkedHashMap<String, Object>();
            metadata.put("schemaVersion", com.chh.autosense.graph.checkpoint.AssistantStateSerializer.SCHEMA_VERSION);
            metadata.put("status", workflow.getStatus()); metadata.put("outputType", output.type());
            metadata.put("outputKey", saved == null ? key : saved.getOutputKey());
            Map<?, ?> payload = output.data().get("payload") instanceof Map<?, ?> value ? value : Map.of();
            String runtimeSource = Objects.toString(payload.get("source"), "");
            boolean mock = Set.of("MOCK", "SIMULATOR").contains(runtimeSource);
            metadata.put("simulated", properties.mode().equals("stub") || mock);
            if (Set.of("MOCK", "SIMULATOR", "REAL").contains(runtimeSource)) metadata.put("runtimeSource", runtimeSource);
            if (initialized) {
                metadata.put("deviceCount", payload.getOrDefault("deviceCount", null));
                metadata.put("degradedCount", payload.getOrDefault("degradedCount", null));
            }
            if (output.data().get("stepType") != null) metadata.put("stepType", output.data().get("stepType"));
            var event = audit.append(workflow, key, initialized ? "DEVICE_CONTEXT_INITIALIZED" : "WORKFLOW_OUTPUT", stepId, null, null,
                    state.workflow().status() == WorkflowStatus.FAILED ? "FAILED" : "RECORDED", output.code(), saved == null ? null : saved.getId(), metadata);
            var data = new LinkedHashMap<>(output.data()); data.put("eventId", workflow.getRequestId() + ":" + event.getEventSequence());
            data.put("sequence", event.getEventSequence()); data.put("version", workflow.getVersion());
            delta.put(OUTPUT, new OutputContext(output.type(), output.code(), output.message(), data));
            var w = state.workflow();
            delta.put(WORKFLOW, new WorkflowContext(w.status(), w.currentStep(), w.progress(), workflow.getVersion(), w.inputRequestId(),
                    w.prompt(), w.returnNode(), w.failureCode(), w.schemaVersion(), w.graphVersion(), event.getEventSequence(), w.executionFence()));
        }
        workflows.update(workflow);
        var session = sessions.lockById(workflow.getSessionId());
        if (session != null && Objects.equals(session.getActiveWorkflowRequestId(), workflow.getRequestId())) {
            session.setStatus(legacyStatus(state.workflow().status()));
            if (state.workflow().status().terminal()) {
                workflow.setFinishedAt(LocalDateTime.now(ZoneOffset.UTC)); workflows.update(workflow);
                session.setActiveWorkflowRequestId(null);
                if (state.output().type().equals("CONCLUSION") || state.output().type().equals("ERROR")) session.setConclusion(state.output().message());
                sessions.update(session, false);
            } else sessions.update(session);
        }
        return delta;
    }

    private void ensurePlan(WorkflowExecution workflow, AssistantState state) {
        var plan = state.plan().executionPlan();
        if (plan.hash().isBlank()) return;
        if (workflow.getPlanJson() != null) {
            if (!readPlan(workflow.getPlanJson()).hash().equals(plan.hash()))
                throw WorkflowClaimService.conflict("PUBLISHED_PLAN_CHANGED");
            return;
        }
        // The workflow row is already locked. Publish the plan and all steps atomically;
        // locking absent child rows would acquire gap locks across unrelated workflows.
        workflow.setPlanJson(encode(plan)); workflows.update(workflow);
        for (int index = 0; index < plan.steps().size(); index++) {
            var definition = plan.steps().get(index);
            var row = new WorkflowStep(); row.setRequestId(workflow.getRequestId()); row.setStepId(definition.stepId()); row.setOrdinal(index);
            row.setType(definition.type().name()); row.setStatus("PENDING"); row.setMaxRetries(properties.maxRetries());
            row.setRetriesUsed(0); row.setCertainty("NOT_SENT"); steps.insertSelective(row);
        }
    }

    private void saveResult(WorkflowExecution workflow, String stepId, ExecutionPlan.Result result) {
        var step = steps.lock(workflow.getRequestId(), stepId);
        if (step == null) return; // Invalid unpublished plans have no executable step rows.
        if (Set.of("COMPLETED", "SKIPPED", "FAILED", "REJECTED", "NOT_EXECUTED").contains(step.getStatus())) {
            if (!step.getStatus().equals(result.status().name()) || !sameJson(step.getResultJson(), encode(result.data()))
                    || !Objects.equals(step.getCertainty(), result.certainty().name())
                    || !Objects.equals(step.getFailureCode(), result.failureCode()))
                throw WorkflowClaimService.conflict("STEP_RESULT_CONFLICT");
            return;
        }
        step.setStatus(result.status().name()); step.setResultJson(encode(result.data())); step.setFailureCode(result.failureCode());
        step.setCertainty(result.certainty().name()); step.setRetriesUsed(result.retriesUsed()); step.setFinishedAt(LocalDateTime.now(ZoneOffset.UTC));
        steps.update(step);
    }

    private ChatMessage message(long sessionId, String requestId, String stepId, String key, String role, String body) {
        var existing = messages.byOutputKey(requestId, key);
        if (existing != null) {
            if (!Objects.equals(existing.getContent(), body) || !Objects.equals(existing.getRole(), role)
                    || existing.getSessionId() != sessionId) throw WorkflowClaimService.conflict("MESSAGE_OUTPUT_CONFLICT");
            return existing;
        }
        var row = new ChatMessage(); row.setSessionId(sessionId); row.setWorkflowRequestId(requestId); row.setStepId(stepId);
        row.setOutputKey(key); row.setRole(role); row.setContent(body); row.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC)); messages.insert(row);
        return row;
    }
    private String visibleBody(OutputContext output) {
        if (!Set.of("STEP_RESULT", "CONCLUSION", "ERROR", "CLARIFY", "CONFIRM").contains(output.type())) return "";
        if (output.data().get("payload") instanceof Map<?, ?> payload) {
            for (String name : List.of("answer", "diagnosis")) if (payload.get(name) instanceof String text) return text;
        }
        return output.message();
    }
    public String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("Invalid workflow data", e); }
    }
    public ExecutionPlan.Result result(WorkflowStep row) {
        try {
            Map<String, Object> data = row.getResultJson() == null ? Map.of()
                    : json.readValue(row.getResultJson(), new com.fasterxml.jackson.core.type.TypeReference<>() { });
            return new ExecutionPlan.Result(ExecutionPlan.StepStatus.valueOf(row.getStatus()), data,
                    Objects.toString(row.getFailureCode(), ""), ExecutionPlan.Certainty.valueOf(row.getCertainty()), row.getRetriesUsed());
        } catch (JsonProcessingException e) { throw new IllegalStateException("Invalid committed step result", e); }
    }
    private ExecutionPlan readPlan(String value) {
        try { return json.readValue(value, ExecutionPlan.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Invalid published plan", e); }
    }
    private boolean sameJson(String first, String second) {
        try {
            var committed = json.readTree(first);
            if (committed.equals(json.readTree(second))) return true;
            // MySQL JSON double formatting can differ from Java by one ULP. Compare the
            // exact database representation, without introducing a numeric tolerance.
            return committed.equals(json.readTree(steps.normalizeJson(second)));
        }
        catch (JsonProcessingException | IllegalArgumentException e) { return false; }
    }
    public static String legacyStatus(WorkflowStatus status) {
        return switch (status) {
            case COMPLETED -> "COMPLETED_ANSWERED";
            case FAILED, REJECTED, CANCELLED -> "FAILED_REQUEST";
            case WAITING_APPROVAL -> "DEVICE_CONFIRMING";
            case WAITING_INPUT, WAITING_RESUME -> "CLARIFYING";
            default -> "DISPATCHING";
        };
    }
}
