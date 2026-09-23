package com.chh.autosense.core.analysis;

import com.chh.autosense.ai.factory.DiagnosisReasonerServiceFactory;
import com.chh.autosense.core.aftersales.AfterSalesGuideService;
import com.chh.autosense.core.device.rule.FaultRuleEngine;
import com.chh.autosense.core.device.rule.FaultVerdict;
import com.chh.autosense.core.security.AuthUser;
import com.chh.autosense.core.session.memory.GraphChatMemoryAdapter;
import com.chh.autosense.domain.enums.PlanStepType;
import com.chh.autosense.graph.node.*;
import com.chh.autosense.graph.state.*;
import com.chh.autosense.service.knowledge.KnowledgeWorkflowService;
import com.chh.autosense.utils.AiCallLog;
import com.chh.autosense.utils.PromptInputEncoder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

/** Read-only reasoning over committed query evidence. No device client or command executor is available here. */
@Service
@RequiredArgsConstructor
public class DiagnosisWorkflowService {
    private final FaultRuleEngine rules;
    private final DiagnosisReasonerServiceFactory factory;
    private final KnowledgeWorkflowService knowledge;
    private final GraphChatMemoryAdapter memory;
    private final PromptInputEncoder encoder;
    private final AfterSalesGuideService afterSales;
    private final com.chh.autosense.mapper.RepairKnowledgeMapper repairKnowledge;

    public Map<String, Object> diagnose(AssistantState state, Duration remaining) throws Exception {
        var input = memory.input(state);
        Instant deadline = Instant.now().plus(remaining);
        var evidence = new ArrayList<Map<String, Object>>();
        for (var step : state.plan().executionPlan().steps()) {
            if (!state.plan().step().dependsOn().contains(step.stepId()) || step.type() != PlanStepType.DEVICE_QUERY) continue;
            var result = state.plan().results().get(step.stepId());
            if (result != null && result.status() == ExecutionPlan.StepStatus.COMPLETED && !PlanRouter.hasTrustedDeviceEvidence(result.data()))
                throw new StepFailure("MOCK_EVIDENCE_NOT_ALLOWED", ExecutionPlan.Certainty.NOT_SENT);
            if (result != null && result.status() == ExecutionPlan.StepStatus.COMPLETED && result.data().get("evidence") instanceof Map<?, ?> value) {
                var item = new LinkedHashMap<String, Object>(); value.forEach((k, v) -> item.put(k.toString(), v));
                item.put("stepId", step.stepId()); evidence.add(StateData.freeze(item));
            }
        }
        String question = state.plan().step().instruction() + "\n" + input.text();
        Map<String, Object> retrieved = cached("diagnosis-knowledge", () -> {
            var answer = knowledge.answer(new KnowledgeWorkflowService.Request(new AuthUser(state.request().userId()), question,
                    input.history(), true, deadline));
            if (answer.stream() == null) return answer.data();
            try (var ignored = AiTokenStreamAdapter.install(null)) {
                return Map.of("answer", AiTokenStreamAdapter.collect(answer.stream(), answer.prefix(), Duration.between(Instant.now(), deadline)), "sources", List.of());
            }
        });
        var diagnostics = new LinkedHashMap<String, Object>();
        diagnostics.put("evidence", evidence); diagnostics.put("knowledge", retrieved);
        var reasoning = cached("diagnosis-reasoning", () -> {
            var call = AiCallLog.start("diagnosis-reasoner");
            try {
                call.phase(AiCallLog.Phase.SERVICE_SETUP);
                var service = factory.diagnosisReasonerService();
                call.phase(AiCallLog.Phase.MODEL_INVOCATION);
                var conclusion = service.diagnose(encoder.history(input.history()), encoder.text(input.text()),
                        encoder.symptom(state.plan().step().instruction()), encoder.diagnostics(diagnostics));
                call.phase(AiCallLog.Phase.OUTPUT_VALIDATION);
                if (conclusion == null || conclusion.conclusionText() == null || conclusion.conclusionText().isBlank())
                    throw new StepFailure("INVALID_DIAGNOSIS_OUTPUT", ExecutionPlan.Certainty.NOT_SENT);
                call.completed();
                return Map.of("summary", Objects.toString(conclusion.problemSummary(), ""), "diagnosis", conclusion.conclusionText());
            } catch (RuntimeException e) { call.failed(e); throw e; }
        });
        var proposals = new ArrayList<Map<String, Object>>();
        for (var item : evidence) {
            @SuppressWarnings("unchecked") var snapshot = item.get("state") instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.<String, Object>of();
            var verdict = rules.evaluate(Objects.toString(item.get("deviceType"), ""), snapshot, Objects.toString(snapshot.get("running_status"), ""));
            if (verdict.kind() == FaultVerdict.Kind.NORMAL) continue;
            var proposal = new LinkedHashMap<String, Object>();
            proposal.put("kind", verdict.kind().name()); proposal.put("reason", Objects.toString(verdict.reason(), ""));
            proposal.put("deviceRef", item.get("deviceRef")); proposal.put("evidenceStepId", item.get("stepId"));
            if (verdict.actionCode() != null) {
                proposal.put("action", verdict.actionCode()); proposal.put("parameters", verdict.actionParams());
                proposal.put("requiresSeparateControlStep", true);
            }
            if (verdict.knowledgeRef() != null) {
                proposal.put("knowledgeRef", verdict.knowledgeRef());
                var manual = repairKnowledge.selectOneByQuery(com.mybatisflex.core.query.QueryWrapper.create()
                        .where("device_type = ?", item.get("deviceType")).and("problem_pattern LIKE ?", "%" + verdict.knowledgeRef() + "%")
                        .orderBy("id", true).limit(1));
                if (manual != null && manual.getManualSteps() != null) proposal.put("manualSteps", manual.getManualSteps().lines().filter(s -> !s.isBlank()).toList());
            }
            if (verdict.kind() == FaultVerdict.Kind.AFTERSALES) {
                var hotline = afterSales.officialHotline();
                proposal.put("afterSales", Map.of("name", hotline.name(), "phone", hotline.phone()));
            }
            proposals.add(StateData.freeze(proposal));
        }
        var result = new LinkedHashMap<>(reasoning);
        if ("AFTERSALES".equals(state.plan().step().diagnosisMode())) {
            var locations = afterSales.searchNearby(input.text());
            if (locations.isEmpty()) locations = List.of(afterSales.officialHotline());
            result.put("afterSales", locations.stream().map(location -> Map.of("name", location.name(), "address", location.address(), "phone", location.phone())).toList());
        }
        result.put("evidence", evidence); result.put("proposal", proposals); result.put("sources", retrieved.getOrDefault("sources", List.of()));
        result.put("knowledge", retrieved.getOrDefault("answer", ""));
        result.put("answer", reasoning.get("diagnosis") + "\n" + retrieved.getOrDefault("answer", "") + "\n" + proposals + "\n" + result.getOrDefault("afterSales", "")
                + (evidence.isEmpty() ? "\n未取得已确认的设备实时数据，本次仅依据问题描述和知识资料分析。" : "")
                + "\n本诊断步骤未执行设备操作。如需新增查询、修复或复检，请重新提交请求以生成独立步骤并确认。");
        return StateData.freeze(result);
    }

    @FunctionalInterface private interface Work { Map<String, Object> run() throws Exception; }
    @SuppressWarnings("unchecked") private Map<String, Object> cached(String key, Work work) throws Exception {
        AttemptCalls scope;
        try { scope = AttemptCalls.current(); } catch (IllegalStateException absent) { return work.run(); }
        return (Map<String, Object>) scope.call(key, ignored -> work.run());
    }
}
