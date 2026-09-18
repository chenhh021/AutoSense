package com.chh.autosense.graph.node;

import com.chh.autosense.domain.enums.WorkflowStatus;
import com.chh.autosense.graph.state.AssistantState;
import java.util.*;

/** No model or device calls: summarizes already committed facts. */
public final class ResponseAggregator {
    public Map<String, Object> apply(AssistantState state) {
        var results = state.plan().executionPlan().steps().stream().map(step -> {
            var result = state.plan().results().get(step.stepId());
            return Map.<String, Object>of("stepId", step.stepId(), "status", result.status().name(), "result", result.data());
        }).toList();
        String answer = state.plan().executionPlan().steps().stream()
                .map(step -> Objects.toString(state.plan().results().get(step.stepId()).data().get("answer"), ""))
                .filter(text -> !text.isBlank()).collect(java.util.stream.Collectors.joining("\n\n"));
        return GraphUpdates.event(state, WorkflowStatus.COMPLETED, "CONCLUSION", "COMPLETED",
                state.plan().candidateOutcome().equals("OUT_OF_SCOPE") ? "我可以帮助您咨询 IoT 知识、查询设备、诊断故障或安全控制设备。"
                        : answer.isBlank() ? "本次计划已完成。" : answer, Map.of("steps", results));
    }
}
