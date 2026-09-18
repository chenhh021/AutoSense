package com.chh.autosense.graph.node;

import com.chh.autosense.graph.state.*;
import java.time.Duration;
import java.util.Map;

/** Business capabilities used by graph nodes; durable effects are guarded by PersistentWorkflowActions. */
public class RealWorkflowActions implements WorkflowStepActions {
    private final com.chh.autosense.ai.factory.IntentPlannerServiceFactory planner;
    private final com.chh.autosense.core.session.memory.GraphChatMemoryAdapter memory;
    private final com.chh.autosense.utils.PromptInputEncoder encoder;
    private final com.chh.autosense.service.knowledge.KnowledgeWorkflowService knowledge;
    private final com.chh.autosense.core.device.DeviceQueryService queries;
    private final com.chh.autosense.core.analysis.DiagnosisWorkflowService diagnosis;
    private final com.chh.autosense.core.repair.RepairExecutor repair;
    public RealWorkflowActions(com.chh.autosense.ai.factory.IntentPlannerServiceFactory planner,
            com.chh.autosense.core.session.memory.GraphChatMemoryAdapter memory, com.chh.autosense.utils.PromptInputEncoder encoder,
            com.chh.autosense.service.knowledge.KnowledgeWorkflowService knowledge,
            com.chh.autosense.core.device.DeviceQueryService queries,
            com.chh.autosense.core.analysis.DiagnosisWorkflowService diagnosis,
            com.chh.autosense.core.repair.RepairExecutor repair) {
        this.planner = planner; this.memory = memory; this.encoder = encoder; this.knowledge = knowledge;
        this.queries = queries; this.diagnosis = diagnosis; this.repair = repair;
    }
    @Override public boolean streamsAnswers() { return true; }
    private StepFailure unavailable() { return new StepFailure("CAPABILITY_NOT_AVAILABLE", ExecutionPlan.Certainty.NOT_SENT); }
    @Override public PlanProposal plan(AssistantState state, Duration remaining) throws Exception {
        var call = com.chh.autosense.utils.AiCallLog.start("intentPlanner");
        try {
            var input = memory.input(state);
            String history = encoder.history(input.history()), text = encoder.text(input.text());
            call.phase(com.chh.autosense.utils.AiCallLog.Phase.SERVICE_SETUP);
            var service = planner.intentPlannerService();
            call.phase(com.chh.autosense.utils.AiCallLog.Phase.MODEL_INVOCATION);
            var candidate = service.plan(history, text);
            call.phase(com.chh.autosense.utils.AiCallLog.Phase.OUTPUT_VALIDATION);
            if (candidate == null) throw new IllegalArgumentException("Missing candidate plan");
            var result = candidate.proposal(); call.completed(); return result;
        } catch (RuntimeException e) {
            call.failed(e);
            for (Throwable cause = e; cause != null; cause = cause.getCause())
                if (cause instanceof java.net.SocketTimeoutException || cause instanceof java.net.http.HttpTimeoutException || cause instanceof java.util.concurrent.TimeoutException)
                    throw new java.util.concurrent.TimeoutException("Model request timed out");
            throw new StepFailure(com.chh.autosense.ai.factory.AiFailureMapping.isTransportFailure(e) ? "AI_SERVICE_UNAVAILABLE" : "PLAN_INVALID", ExecutionPlan.Certainty.NOT_SENT);
        }
    }
    @Override public Map<String, Object> resolveTarget(AssistantState state) { return queries.resolve(state); }
    @Override public void revalidate(AssistantState state) { queries.validate(state); }
    @Override public Map<String, Object> answer(AssistantState state, Duration remaining) throws Exception {
        var input = memory.input(state);
        var deadline = java.time.Instant.now().plus(remaining);
        var answer = knowledge.answer(new com.chh.autosense.service.knowledge.KnowledgeWorkflowService.Request(
                new com.chh.autosense.core.security.AuthUser(state.request().userId()), input.text(), input.history(),
                Boolean.TRUE.equals(state.plan().step().requiresKnowledgeBase()), deadline));
        if (answer.stream() == null) return answer.data();
        return Map.of("answer", AiTokenStreamAdapter.collect(answer.stream(), answer.prefix(), java.time.Duration.between(java.time.Instant.now(), deadline)), "sources", java.util.List.of());
    }
    @Override public Map<String, Object> query(AssistantState state, Duration remaining) { return queries.query(state); }
    @Override public Map<String, Object> diagnose(AssistantState state, Duration remaining) throws Exception { return diagnosis.diagnose(state, remaining); }
    @Override public Map<String, Object> control(AssistantState state, Duration remaining) { return repair.executeApproved(state); }
    @Override public void prepareCommand(AssistantState state) { throw unavailable(); }
    @Override public void commitResult(AssistantState state, ExecutionPlan.Result result) { throw unavailable(); }
    @Override public boolean supportsSafeCommandRetry() { return false; }
}
