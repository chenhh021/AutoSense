package com.chh.autosense.graph.node;

import com.chh.autosense.graph.state.*;

import java.time.Duration;
import java.util.Map;

/**
 * Business capabilities used by graph nodes; durable effects are guarded by PersistentWorkflowActions.
 */
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
        this.planner = planner;
        this.memory = memory;
        this.encoder = encoder;
        this.knowledge = knowledge;
        this.queries = queries;
        this.diagnosis = diagnosis;
        this.repair = repair;
    }

    private com.chh.autosense.config.DeviceQueryProperties deviceProperties;
    private com.chh.autosense.service.DeviceListService deviceLists;
    private com.chh.autosense.service.knowledge.UserAiServiceCache aiServices;
    private final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public RealWorkflowActions(com.chh.autosense.ai.factory.IntentPlannerServiceFactory planner,
                               com.chh.autosense.core.session.memory.GraphChatMemoryAdapter memory, com.chh.autosense.utils.PromptInputEncoder encoder,
                               com.chh.autosense.service.knowledge.KnowledgeWorkflowService knowledge,
                               com.chh.autosense.core.device.DeviceQueryService queries,
                               com.chh.autosense.core.analysis.DiagnosisWorkflowService diagnosis,
                               com.chh.autosense.core.repair.RepairExecutor repair,
                               com.chh.autosense.config.DeviceQueryProperties deviceProperties,
                               com.chh.autosense.service.DeviceListService deviceLists,
                               com.chh.autosense.service.knowledge.UserAiServiceCache aiServices) {
        this(planner, memory, encoder, knowledge, queries, diagnosis, repair);
        this.deviceProperties = deviceProperties;
        this.deviceLists = deviceLists;
        this.aiServices = aiServices;
    }

    private String checkSize(AssistantState.DeviceContext context) {
        String encoded = encoder.deviceContext(context);
        if (encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > (deviceProperties == null ? 262144 : deviceProperties.maxPlannerDeviceBytes()))
            throw new com.chh.autosense.exception.ApiException(com.chh.autosense.exception.ErrorCode.DEVICE_CONTEXT_TOO_LARGE,
                    "Device context exceeds the configured size limit");
        return encoded;
    }

    @Override
    public boolean streamsAnswers() {
        return true;
    }

    private StepFailure unavailable() {
        return new StepFailure("CAPABILITY_NOT_AVAILABLE", ExecutionPlan.Certainty.NOT_SENT);
    }

    @Override
    public PlanProposal plan(AssistantState state, Duration remaining) throws Exception {
        var call = com.chh.autosense.utils.AiCallLog.start("intentPlanner");
        try (var scope = com.chh.autosense.core.security.PlannerInvocationContext.open(state, remaining)) {
            var input = memory.input(state);
            String history = encoder.history(input.history()), text = encoder.text(input.text());
            call.phase(com.chh.autosense.utils.AiCallLog.Phase.SERVICE_SETUP);
            var service = planner.intentPlannerService();
            call.phase(com.chh.autosense.utils.AiCallLog.Phase.MODEL_INVOCATION);
            var facts = new java.util.LinkedHashMap<String, Object>(json.readValue(checkSize(scope.deviceContext()),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { }));
            facts.put("initialized", scope.deviceContext().initialized());
            facts.put("userId", state.request().userId());
            var candidate = service.plan(history, text, encoder.diagnostics(facts),
                    encoder.text(state.request().userMessage()));
            scope.checkHealthy();
            call.phase(com.chh.autosense.utils.AiCallLog.Phase.OUTPUT_VALIDATION);
            if (candidate == null) throw new IllegalArgumentException("Missing candidate plan");
            var result = candidate.proposal();
            call.completed();
            checkSize(scope.deviceContext());
            return new PlanProposal(result.outcome(), result.steps(), result.clarifyQuestion(), scope.deviceContext());
        } catch (RuntimeException e) {
            call.failed(e);
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof com.chh.autosense.exception.ApiException api) throw api;
                if (cause instanceof SecurityException security) throw security;
                if (cause instanceof java.net.SocketTimeoutException || cause instanceof java.net.http.HttpTimeoutException || cause instanceof java.util.concurrent.TimeoutException)
                    throw new java.util.concurrent.TimeoutException("Model request timed out");
            }
            throw new StepFailure(com.chh.autosense.ai.factory.AiFailureMapping.isTransportFailure(e) ? "AI_SERVICE_UNAVAILABLE" : "PLAN_INVALID", ExecutionPlan.Certainty.NOT_SENT);
        }
    }

    @Override
    public Map<String, Object> resolveTarget(AssistantState state) {
        return queries.resolve(state);
    }

    @Override
    public void revalidate(AssistantState state) {
        queries.validate(state);
    }

    @Override
    public Map<String, Object> answer(AssistantState state, Duration remaining) throws Exception {
        if (state.plan().step().dependsOn().stream().map(state.plan().results()::get)
                .filter(java.util.Objects::nonNull).anyMatch(result -> PlanRouter.isMock(result.data())))
            return Map.of("answer", "前序设备属性为模拟数据或来源尚未验证，仅供展示。需要真实参数后才能解释、比较、估算或提出设备建议。");
        var input = memory.input(state);
        var deadline = java.time.Instant.now().plus(remaining);
        if ("DEVICE_CONTEXT".equals(state.plan().runtimeInputs().getOrDefault("answerMode", state.plan().step().parameters().get("answerMode")))) {
            var actor = new com.chh.autosense.core.security.AuthUser(state.request().userId());
            var snapshot = state.<AssistantState.DeviceContext>value(AssistantState.DEVICE).orElseThrow();
            var current = deviceLists.localMine(actor).stream().collect(java.util.stream.Collectors.toMap(
                    com.chh.autosense.domain.vo.DeviceView::id, java.util.function.Function.identity()));
            for (var saved : snapshot.planningDevices()) {
                var device = current.get(saved.id());
                if (device == null || !java.util.Objects.equals(device.sn(), saved.sn())
                        || !java.util.Objects.equals(device.deviceTypeCode(), saved.deviceType())
                        || !java.util.Objects.equals(device.deviceModelCode(), saved.deviceModel()))
                    throw new StepFailure("FORBIDDEN", ExecutionPlan.Certainty.NOT_SENT);
            }
            var call = com.chh.autosense.utils.AiCallLog.start("deviceContextAnswer");
            try {
                call.phase(com.chh.autosense.utils.AiCallLog.Phase.SERVICE_SETUP);
                var service = aiServices.getOrCreate(actor).direct();
                call.phase(com.chh.autosense.utils.AiCallLog.Phase.MODEL_INVOCATION);
                String answer = AiTokenStreamAdapter.collect(service.answerDeviceContext(encoder.history(input.history()),
                        encoder.text(input.text()), checkSize(snapshot)), "", java.time.Duration.between(java.time.Instant.now(), deadline));
                call.completed();
                return Map.of("answer", answer, "answerMode", "DEVICE_CONTEXT", "sources", java.util.List.of());
            } catch (Exception e) {
                call.failed(e);
                throw e;
            }
        }
        var answer = knowledge.answer(new com.chh.autosense.service.knowledge.KnowledgeWorkflowService.Request(
                new com.chh.autosense.core.security.AuthUser(state.request().userId()), input.text(), input.history(),
                Boolean.TRUE.equals(state.plan().step().requiresKnowledgeBase()), deadline));
        if (answer.stream() == null) return answer.data();
        return Map.of("answer", AiTokenStreamAdapter.collect(answer.stream(), answer.prefix(), java.time.Duration.between(java.time.Instant.now(), deadline)), "sources", java.util.List.of());
    }

    @Override
    public Map<String, Object> query(AssistantState state, Duration remaining) throws Exception {
        var deadline = java.time.Instant.now().plus(remaining);
        var result = queries.query(state);
        if (PlanRouter.isMock(result)) {
            var output = new java.util.LinkedHashMap<>(result);
            output.put("answer", "当前为本地生成的模拟数据，需要真实参数后才能回答设备实际状况。");
            return StateData.freeze(output);
        }
        if (!java.time.Instant.now().isBefore(deadline)) throw new java.util.concurrent.TimeoutException("Device answer deadline exceeded");
        var input = memory.input(state);
        var target = state.<AssistantState.DeviceContext>value(AssistantState.DEVICE).orElseThrow().resolved();
        var context = Map.of("deviceName", target.getOrDefault("name", ""),
                "values", result.get("displayValues"));
        var call = com.chh.autosense.utils.AiCallLog.start("deviceQueryAnswer");
        try {
            call.phase(com.chh.autosense.utils.AiCallLog.Phase.SERVICE_SETUP);
            var service = aiServices.getOrCreate(new com.chh.autosense.core.security.AuthUser(state.request().userId())).direct();
            call.phase(com.chh.autosense.utils.AiCallLog.Phase.MODEL_INVOCATION);
            String answer = AiTokenStreamAdapter.collect(service.answerDeviceQuery(encoder.history(input.history()),
                    encoder.text(state.request().userMessage()), encoder.text(state.plan().step().instruction()),
                    encoder.diagnostics(context)), "", java.time.Duration.between(java.time.Instant.now(), deadline));
            var output = new java.util.LinkedHashMap<>(result); output.put("answer", answer);
            call.completed();
            return StateData.freeze(output);
        } catch (Exception e) { call.failed(e); throw e; }
    }

    @Override
    public Map<String, Object> diagnose(AssistantState state, Duration remaining) throws Exception {
        return diagnosis.diagnose(state, remaining);
    }

    @Override
    public Map<String, Object> control(AssistantState state, Duration remaining) {
        return repair.executeApproved(state);
    }

    @Override
    public void prepareCommand(AssistantState state) {
        throw unavailable();
    }

    @Override
    public void commitResult(AssistantState state, ExecutionPlan.Result result) {
        throw unavailable();
    }

    @Override
    public boolean supportsSafeCommandRetry() {
        return false;
    }
}
