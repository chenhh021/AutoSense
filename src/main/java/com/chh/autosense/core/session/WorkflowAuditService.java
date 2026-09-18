package com.chh.autosense.core.session;

import com.chh.autosense.domain.entity.*;
import com.chh.autosense.graph.state.StateData;
import com.chh.autosense.mapper.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;
import java.time.*;
import java.util.*;

/** Appends facts within the caller's row-locked transaction; never stores conversation bodies here. */
@Service
@Slf4j
public class WorkflowAuditService {
    private static final Set<String> META_KEYS = Set.of("schemaVersion", "status", "stepType", "simulated", "approvalId",
            "inputRequestId", "expiresAt", "failureCode", "certainty", "retriesUsed", "fence", "version", "outputType",
            "outputKey", "sourceRefs", "scopeHash", "decision", "node", "completed", "skipped", "notExecuted");
    private final RepairActionLogMapper audit;
    private final WorkflowExecutionMapper workflows;
    private final ObjectMapper json;
    public WorkflowAuditService(RepairActionLogMapper audit, WorkflowExecutionMapper workflows, ObjectMapper json) {
        this.audit = audit; this.workflows = workflows; this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public RepairActionLog append(WorkflowExecution workflow, String key, String type, String stepId,
            String commandId, String attemptId, String result, String code, Long messageId, Map<String, Object> metadata) {
        var existing = audit.byEventKey(workflow.getRequestId(), key);
        if (existing != null) return existing;
        if (key == null || key.length() > 128 || type == null || type.length() > 64
                || result == null || result.length() > 16 || code != null && code.length() > 64
                || !META_KEYS.containsAll(metadata.keySet())) throw new IllegalArgumentException("Invalid audit metadata");
        long sequence = workflow.getLastEventSequence() + 1;
        var row = new RepairActionLog();
        row.setSessionId(workflow.getSessionId()); row.setRequestId(workflow.getRequestId()); row.setStepId(stepId);
        row.setCommandExecutionId(commandId); row.setAttemptId(attemptId); row.setActorUserId(workflow.getUserId());
        row.setActionCode(type); row.setEventType(type); row.setOperationKind(Objects.toString(metadata.get("stepType"), type));
        row.setEventSequence(sequence); row.setEventKey(key); row.setResult(result); row.setResultCode(code);
        row.setChatMessageRef(messageId); row.setSchemaVersion(1); row.setMessage(type);
        try { row.setParams(json.writeValueAsString(StateData.freeze(metadata))); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("Invalid audit data", e); }
        row.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC)); audit.insert(row);
        workflow.setLastEventSequence(sequence); workflow.setVersion(workflow.getVersion() + 1);
        workflow.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC)); workflows.update(workflow);
        String requestId = workflow.getRequestId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                log.info("Workflow event committed: workflowRequestId={}, stepId={}, eventType={}, result={}", requestId, stepId, type, result);
            }
        });
        return row;
    }
}
