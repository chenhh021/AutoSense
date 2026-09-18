package com.chh.autosense.graph;

import com.chh.autosense.config.GraphProperties;
import com.chh.autosense.domain.enums.PlanStepType;
import com.chh.autosense.graph.node.PlanValidator;
import com.chh.autosense.graph.node.WorkflowStepActions.PlanProposal;
import com.chh.autosense.graph.state.ExecutionPlan;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class PlanValidationTest {
    @Test void unknownTypesRequireClarificationAndExtraModelFieldsAreRejected() throws Exception {
        var candidate = com.chh.autosense.ai.model.ExecutionPlanCandidate.decode("""
                {"outcome":"PLAN","steps":[{"stepId":"s1","type":"EXEC_TOOL","instruction":"run"}]}
                """);
        assertThatThrownBy(() -> validator.validate(candidate.proposal())).isInstanceOf(PlanValidator.ClarificationRequired.class);
        for (String field : List.of("approved", "permission", "status", "timeout", "retryBudget")) {
            assertThatThrownBy(() -> com.chh.autosense.ai.model.ExecutionPlanCandidate.decode(
                    "{\"outcome\":\"PLAN\",\"steps\":[],\"" + field + "\":true}"))
                    .isInstanceOf(com.fasterxml.jackson.databind.JsonMappingException.class);
        }
    }

    @Test void boundsConditionDepthAndRequiresExplicitReferenceDependencies() {
        var ref = new ExecutionPlan.Operand(new ExecutionPlan.Reference("s1", "brightness"), null);
        var condition = new ExecutionPlan.Condition("LT", ref, new ExecutionPlan.Operand(null, 30), List.of());
        for (int i = 0; i < 4; i++) condition = new ExecutionPlan.Condition("AND", null, null, List.of(condition, condition));
        var deep = new ExecutionPlan.Step("s2", PlanStepType.DEVICE_CONTROL, "control", "lamp", Map.of(),
                List.of("s1"), Map.of(), condition, null, null);
        assertThatThrownBy(() -> validator.validate(new PlanProposal("PLAN", List.of(
                step("s1", PlanStepType.DEVICE_QUERY, List.of()), deep), null))).isInstanceOf(IllegalArgumentException.class);
        var missing = new ExecutionPlan.Step("s2", PlanStepType.DEVICE_CONTROL, "control", "lamp", Map.of(),
                List.of(), Map.of("deviceRef", new ExecutionPlan.Reference("s1", "deviceRef")), null, null, null);
        assertThatThrownBy(() -> validator.validate(new PlanProposal("PLAN", List.of(
                step("s1", PlanStepType.DEVICE_QUERY, List.of()), missing), null))).isInstanceOf(IllegalArgumentException.class);
    }

    private final PlanValidator validator = new PlanValidator(
            new GraphProperties("stub", 8, 30, null, 2, 0, 300, 30, 300, 256));

    static ExecutionPlan.Step step(String id, PlanStepType type, List<String> dependencies) {
        return new ExecutionPlan.Step(id, type, "instruction", "lamp", Map.of(), dependencies,
                Map.of(), null, type == PlanStepType.KNOWLEDGE_CONSULT ? false : null, null);
    }

    @Test void acceptsAnOrderedPlanAndKeepsKnowledgeDecision() {
        var steps = List.of(step("s1", PlanStepType.KNOWLEDGE_CONSULT, List.of()),
                step("s2", PlanStepType.DEVICE_QUERY, List.of("s1")));
        var result = validator.validate(new PlanProposal("PLAN", steps, null));
        assertThat(result.steps()).hasSize(2);
        assertThat(result.steps().getFirst().requiresKnowledgeBase()).isFalse();
        assertThat(result.hash()).isNotBlank();
    }

    @Test void rejectsDuplicateForwardAndMissingDependencies() {
        for (List<ExecutionPlan.Step> steps : List.of(
                List.of(step("s1", PlanStepType.DEVICE_QUERY, List.of("s2")),
                        step("s2", PlanStepType.DEVICE_CONTROL, List.of())),
                List.of(step("s1", PlanStepType.DEVICE_QUERY, List.of()),
                        step("s1", PlanStepType.DEVICE_QUERY, List.of())),
                List.of(step("s1", PlanStepType.DEVICE_QUERY, List.of("missing"))))) {
            assertThatThrownBy(() -> validator.validate(new PlanProposal("PLAN", steps, null)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void rejectsInjectedAuthorizationAndInvalidConditionOperator() {
        var unsafe = new ExecutionPlan.Step("s1", PlanStepType.DEVICE_CONTROL, "control", "lamp",
                Map.of("approved", true), List.of(), Map.of(), null, null, null);
        assertThatThrownBy(() -> validator.validate(new PlanProposal("PLAN", List.of(unsafe), null)))
                .isInstanceOf(IllegalArgumentException.class);
        var scripted = new ExecutionPlan.Step("s2", PlanStepType.DEVICE_CONTROL, "control", "lamp",
                Map.of(), List.of(), Map.of(),
                new ExecutionPlan.Condition("EXEC", null, null, List.of()), null, null);
        assertThatThrownBy(() -> validator.validate(new PlanProposal("PLAN", List.of(scripted), null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void emptyStepsAreAllowedOnlyForExplicitClarifyOrOutOfScope() {
        assertThat(validator.validate(new PlanProposal("CLARIFY", List.of(), "Which device?" )).steps()).isEmpty();
        assertThat(validator.validate(new PlanProposal("OUT_OF_SCOPE", List.of(), null)).steps()).isEmpty();
        assertThatThrownBy(() -> validator.validate(new PlanProposal("PLAN", List.of(), null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void boundsPlanLengthAndRequiresTheKnowledgeRetrievalDecision() {
        var tooMany = new ArrayList<ExecutionPlan.Step>();
        for (int i = 0; i < 9; i++) tooMany.add(step("s" + i, PlanStepType.DEVICE_QUERY, List.of()));
        assertThatThrownBy(() -> validator.validate(new PlanProposal("PLAN", tooMany, null)))
                .isInstanceOf(IllegalArgumentException.class);
        var missing = new ExecutionPlan.Step("s1", PlanStepType.KNOWLEDGE_CONSULT, "question", "",
                Map.of(), List.of(), Map.of(), null, null, null);
        assertThatThrownBy(() -> validator.validate(new PlanProposal("PLAN", List.of(missing), null)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
