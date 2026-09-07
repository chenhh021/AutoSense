package com.chh.autosense.core.routing;

import com.chh.autosense.ai.model.RoutingDecision;
import com.chh.autosense.ai.model.enums.*;
import com.chh.autosense.domain.enums.AssistantCapability;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class RoutingDecisionValidator {
    public static RoutingDecision clarification() {
        return new RoutingDecision(RoutingOutcome.CLARIFY, null, null, null,
                "请说明您希望咨询知识、查询设备、诊断故障，还是控制设备；本轮先处理一项。 ");
    }

    public RoutingDecision validate(RoutingDecision candidate) {
        boolean valid = candidate != null && candidate.outcome() != null;
        if (valid && candidate.outcome() == RoutingOutcome.SINGLE) {
            valid = candidate.intent() != null && candidate.clarifyQuestion() == null
                    && (candidate.intent() == CapabilityIntent.DIAGNOSIS
                    ? candidate.diagnosisMode() != null : candidate.diagnosisMode() == null);
        } else if (valid) {
            valid = candidate.intent() == null && candidate.diagnosisMode() == null;
        }
        if (!valid) {
            log.warn("Routing clarification required: reasonCode=INVALID_OUTPUT");
            return clarification();
        }
        log.info("Intent routed: outcome={}, capability={}, diagnosisMode={}", candidate.outcome(),
                candidate.intent(), candidate.diagnosisMode());
        if (candidate.outcome() == RoutingOutcome.CLARIFY || candidate.outcome() == RoutingOutcome.COMPOSITE) {
            // A model's free-form clarification can expose internal material; use the public fixed question.
            return new RoutingDecision(candidate.outcome(), null, null, null, clarification().clarifyQuestion());
        }
        return candidate;
    }

    public AssistantCapability capability(RoutingDecision decision) {
        if (decision.outcome() != RoutingOutcome.SINGLE || decision.intent() == null) {
            throw new IllegalArgumentException("Single capability required");
        }
        return switch (decision.intent()) {
            case KNOWLEDGE -> AssistantCapability.KNOWLEDGE;
            case DEVICE_QUERY -> AssistantCapability.DEVICE_QUERY;
            case DIAGNOSIS -> AssistantCapability.DIAGNOSIS;
            case CONTROL -> AssistantCapability.CONTROL;
        };
    }
}
