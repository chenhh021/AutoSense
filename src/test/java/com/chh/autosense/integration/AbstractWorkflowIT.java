package com.chh.autosense.integration;

import com.chh.autosense.core.security.AuthUser;
import com.chh.autosense.core.session.*;
import com.chh.autosense.graph.checkpoint.MyBatisCheckpointSaver;
import com.chh.autosense.graph.state.AssistantState;
import com.chh.autosense.mapper.WorkflowExecutionMapper;
import org.bsc.langgraph4j.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import java.util.*;

@ActiveProfiles("test")
@TestPropertySource(properties = "autosense.graph.mode=stub")
abstract class AbstractWorkflowIT extends AbstractIntegrationIT {
    @Autowired CompiledGraph<AssistantState> graph;
    @Autowired WorkflowPersistenceService persistence;
    @Autowired WorkflowClaimService claims;
    @Autowired WorkflowApprovalService approvals;
    @Autowired WorkflowExecutionMapper workflows;
    @Autowired JdbcTemplate jdbc;

    AcceptedWorkflow start(String text) throws Exception {
        var accepted = persistence.admit(new AuthUser(1L), null, text);
        var workflow = workflows.selectOneById(accepted.requestId());
        var claim = claims.acquire(accepted.requestId(), 1, workflow.getVersion());
        try {
            var data = new LinkedHashMap<>(AssistantState.initial(new AssistantState.RequestContext(accepted.requestId(), accepted.sessionId(), 1, text)));
            var state = new AssistantState(data);
            data.put(AssistantState.WORKFLOW, fenced(state.workflow(), claim.fence()));
            data.put(AssistantState.AUDIT, new AssistantState.AuditContext(accepted.requestId(), accepted.reportId(), accepted.messageId(), accepted.round(), List.of(), List.of()));
            for (var ignored : graph.stream(GraphInput.args(data), config(claim))) { }
        } finally { claims.release(claim); }
        return accepted;
    }

    AssistantState state(AcceptedWorkflow accepted) throws Exception {
        return graph.getState(RunnableConfig.builder().threadId(accepted.requestId()).build()).state();
    }
    void decide(AcceptedWorkflow accepted, boolean decision) throws Exception {
        var state = state(accepted); var approval = state.control().approvalRef();
        var outcome = approvals.decide(new AuthUser(1L), accepted.sessionId(), accepted.requestId(), approval.stepId(), approval.approvalId(),
                decision, workflows.selectOneById(accepted.requestId()).getVersion());
        if (!outcome.execute()) return;
        var claim = claims.acquire(accepted.requestId(), 1, outcome.version());
        try {
            var c = state.control();
            var updated = graph.updateState(config(claim), Map.of(AssistantState.WORKFLOW, fenced(state.workflow(), claim.fence()), AssistantState.CONTROL,
                    new AssistantState.ControlContext(c.command(), c.commandExecutionId(), c.permission(), c.risk(), outcome.approval(), c.idempotencyKey(), c.executionResult())));
            for (var ignored : graph.stream(GraphInput.resume(), updated)) { }
        } finally { claims.release(claim); }
    }
    RunnableConfig config(WorkflowClaimService.Claim claim) {
        return RunnableConfig.builder().threadId(claim.requestId()).build().updateMetadata(Map.of(MyBatisCheckpointSaver.EXECUTION_FENCE, claim.fence()));
    }
    AssistantState.WorkflowContext fenced(AssistantState.WorkflowContext w, long fence) {
        return new AssistantState.WorkflowContext(w.status(), w.currentStep(), w.progress(), w.version(), w.inputRequestId(), w.prompt(),
                w.returnNode(), w.failureCode(), w.schemaVersion(), w.graphVersion(), w.lastOutputSequence(), fence);
    }
}
