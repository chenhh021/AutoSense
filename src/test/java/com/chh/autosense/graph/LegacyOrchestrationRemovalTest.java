package com.chh.autosense.graph;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class LegacyOrchestrationRemovalTest {
    private static final List<String> REMOVED = List.of(
            "core.session.SessionOrchestrator", "core.session.SessionProcessingService",
            "core.session.SessionTransitionLog", "core.session.SessionContext", "core.session.SessionContextStore",
            "core.session.RepairExecutionRunner", "core.session.AcceptedConversationInitializer",
            "core.session.statemachine.SessionStateMachine", "core.routing.CapabilityDispatcher",
            "core.routing.KnowledgeCapabilityHandler", "core.routing.DirectAnswerer", "core.routing.IntentClassifier",
            "ai.IntentRouterService", "ai.factory.IntentRouterServiceFactory", "ai.model.RoutingDecision",
            "config.AssistantProperties");

    @Test void obsoleteSourcesClassesAndPromptCannotBeLoaded() {
        for (String name : REMOVED) {
            assertThat(Path.of("src/main/java/com/chh/autosense", name.replace('.', '/') + ".java")).doesNotExist();
            assertThatThrownBy(() -> Class.forName("com.chh.autosense." + name)).isInstanceOf(ClassNotFoundException.class);
        }
        assertThat(getClass().getResource("/prompt/intent-router.txt")).isNull();
    }

    @Test void productionSourcesCannotReferenceTheOldEngineOrMemorySaver() throws Exception {
        try (var paths = Files.walk(Path.of("src/main/java"))) {
            for (var path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(path);
                for (String name : REMOVED) assertThat(source).as(path.toString()).doesNotContain(name.substring(name.lastIndexOf('.') + 1));
                assertThat(source).doesNotContain("MemorySaver", "continueWaiting", "setTextSink");
            }
        }
        assertThat(Files.readString(Path.of("src/main/resources/application.yaml"))).doesNotContain("ASSISTANT_CONTEXT_TTL", "ASSISTANT_PROCESSING_TIMEOUT", "  assistant:");
    }
}
