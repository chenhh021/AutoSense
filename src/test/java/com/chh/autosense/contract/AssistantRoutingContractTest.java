package com.chh.autosense.contract;

import com.chh.autosense.ai.model.RoutingDecision;
import com.chh.autosense.ai.model.enums.*;
import com.chh.autosense.core.routing.*;
import com.chh.autosense.core.security.AuthUser;
import com.chh.autosense.core.session.memory.ConversationHistorySnapshot;
import com.chh.autosense.domain.enums.AssistantCapability;
import com.chh.autosense.domain.enums.SessionStatus;
import com.chh.autosense.exception.ApiException;
import com.chh.autosense.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;

class AssistantRoutingContractTest {
    private final RoutingDecisionValidator validator = new RoutingDecisionValidator();
    private final ConversationHistorySnapshot empty = new ConversationHistorySnapshot(10, 20, List.of());

    @Test void eachSingleCapabilityIsMappedExplicitlyAndOnlyItsHandlerReceivesTheRequest() {
        for (CapabilityIntent intent : CapabilityIntent.values()) {
            RoutingDecision decision = validator.validate(new RoutingDecision(RoutingOutcome.SINGLE, intent,
                    intent == CapabilityIntent.DIAGNOSIS ? DiagnosisMode.DEFAULT : null, "unverified target", null));
            AssistantCapability capability = validator.capability(decision);
            AtomicInteger calls = new AtomicInteger();
            AssistantCapabilityHandler handler = handler(capability, calls);
            var dispatcher = new CapabilityDispatcher(List.of(handler));
            var request = new CapabilityRequest(new AuthUser(7L), 10, 11, 1, 20, "question", true,
                    empty, LocalDateTime.now().plusMinutes(2), capability, decision.diagnosisMode(),
                    decision.targetHint(), SessionStatus.ROUTING);
            assertThat(dispatcher.dispatch(request, token -> { }).toCompletableFuture().join().text()).isEqualTo("test receiver");
            assertThat(calls).hasValue(1);
            assertThat(request.user().userId()).isEqualTo(7);
            assertThat(request.targetHint()).isEqualTo("unverified target");
        }
    }

    @Test void invalidOrContradictoryShapesOnlyClarify() {
        for (RoutingDecision input : new RoutingDecision[]{null,
                new RoutingDecision(null, null, null, null, null),
                new RoutingDecision(RoutingOutcome.SINGLE, null, null, null, null),
                new RoutingDecision(RoutingOutcome.CLARIFY, CapabilityIntent.CONTROL, null, null, null),
                new RoutingDecision(RoutingOutcome.SINGLE, CapabilityIntent.KNOWLEDGE, DiagnosisMode.AFTERSALES, null, null),
                new RoutingDecision(RoutingOutcome.SINGLE, CapabilityIntent.DIAGNOSIS, null, null, null)}) {
            var result = validator.validate(input);
            assertThat(result.outcome()).isEqualTo(RoutingOutcome.CLARIFY);
            assertThat(result.intent()).isNull();
            assertThat(result.clarifyQuestion()).isNotBlank();
        }
    }

    @Test void explicitMockKeepsKnowledgeQueryDiagnosisControlAndAmbiguitySeparate() {
        MockIntentClassifier classifier = new MockIntentClassifier();
        assertThat(classifier.classify("LA001型号支持什么功能", empty).intent()).isEqualTo(CapabilityIntent.KNOWLEDGE);
        assertThat(classifier.classify("列出我的所有设备", empty).intent()).isEqualTo(CapabilityIntent.DEVICE_QUERY);
        assertThat(classifier.classify("客厅灯为什么不亮", empty).intent()).isEqualTo(CapabilityIntent.DIAGNOSIS);
        assertThat(classifier.classify("请打开客厅灯", empty).intent()).isEqualTo(CapabilityIntent.CONTROL);
        assertThat(classifier.classify("附近的售后网点", empty).diagnosisMode()).isEqualTo(DiagnosisMode.AFTERSALES);
        for (String compound : List.of("查亮度并调到80", "如果亮度低就打开灯", "打开所有灯")) {
            assertThat(classifier.classify(compound, empty).outcome()).isEqualTo(RoutingOutcome.COMPOSITE);
        }
        assertThat(classifier.classify("帮我看看", empty).outcome()).isEqualTo(RoutingOutcome.CLARIFY);
        assertThat(classifier.classify("写一首诗", empty).outcome()).isEqualTo(RoutingOutcome.OUT_OF_SCOPE);
    }

    @Test void duplicateRegistrationFailsAndMissingCapabilityIsExplicit() {
        var handler = handler(AssistantCapability.CONTROL, new AtomicInteger());
        assertThatThrownBy(() -> new CapabilityDispatcher(List.of(handler, handler))).isInstanceOf(IllegalStateException.class);
        var dispatcher = new CapabilityDispatcher(List.of());
        var request = new CapabilityRequest(new AuthUser(7L), 10, 11, 1, 20, "control", true,
                empty, LocalDateTime.now().plusMinutes(2), AssistantCapability.CONTROL, null, "target", SessionStatus.ROUTING);
        assertThatThrownBy(() -> dispatcher.dispatch(request, token -> { }))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CAPABILITY_NOT_AVAILABLE));
    }

    private AssistantCapabilityHandler handler(AssistantCapability capability, AtomicInteger calls) {
        return new AssistantCapabilityHandler() {
            @Override public AssistantCapability capability() { return capability; }
            @Override public CompletionStage<CapabilityResult> handle(CapabilityRequest request, Consumer<String> sink) {
                calls.incrementAndGet();
                return CompletableFuture.completedFuture(CapabilityResult.answer("test receiver"));
            }
        };
    }
}
