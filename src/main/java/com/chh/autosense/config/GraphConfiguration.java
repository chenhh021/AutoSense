package com.chh.autosense.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import java.time.Clock;
import com.chh.autosense.graph.MainGraphFactory;
import com.chh.autosense.graph.node.StubWorkflowActions;
import com.chh.autosense.graph.node.WorkflowStepActions;
import com.chh.autosense.graph.state.AssistantState;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Qualifier;
import com.chh.autosense.core.session.*;
import com.chh.autosense.graph.node.PersistentWorkflowActions;

@Configuration(proxyBeanMethods = false)
public class GraphConfiguration {
    @Bean("workflowBusinessActions")
    @ConditionalOnProperty(name = "autosense.graph.mode", havingValue = "stub")
    public WorkflowStepActions stubWorkflowActions(Clock workflowClock) {
        return new StubWorkflowActions();
    }

    @Bean("workflowBusinessActions")
    @ConditionalOnProperty(name = "autosense.graph.mode", havingValue = "real", matchIfMissing = true)
    public WorkflowStepActions realWorkflowActions(com.chh.autosense.ai.factory.IntentPlannerServiceFactory planner,
            com.chh.autosense.core.session.memory.GraphChatMemoryAdapter memory, com.chh.autosense.utils.PromptInputEncoder encoder,
            com.chh.autosense.service.knowledge.KnowledgeWorkflowService knowledge,
            com.chh.autosense.core.device.DeviceQueryService queries,
            com.chh.autosense.core.analysis.DiagnosisWorkflowService diagnosis,
            com.chh.autosense.core.repair.RepairExecutor repair,
            DeviceQueryProperties deviceProperties, com.chh.autosense.service.DeviceListService deviceLists,
            com.chh.autosense.service.knowledge.UserAiServiceCache aiServices) {
        return new com.chh.autosense.graph.node.RealWorkflowActions(planner, memory, encoder, knowledge, queries, diagnosis, repair, deviceProperties, deviceLists, aiServices);
    }

    @Bean
    @Primary
    @ConditionalOnBean({WorkflowPersistenceService.class, WorkflowStepActions.class})
    public WorkflowStepActions persistentWorkflowActions(@Qualifier("workflowBusinessActions") WorkflowStepActions actions,
            WorkflowPersistenceService persistence, WorkflowApprovalService approvals, CommandExecutionService commands) {
        return new PersistentWorkflowActions(actions, persistence, approvals, commands);
    }

    @Bean
    @ConditionalOnBean(WorkflowStepActions.class)
    public MainGraphFactory mainGraphFactory(GraphProperties properties, WorkflowStepActions actions, Clock workflowClock) {
        return new MainGraphFactory(properties, actions, workflowClock);
    }

    @Bean
    @ConditionalOnBean({BaseCheckpointSaver.class, MainGraphFactory.class})
    public CompiledGraph<AssistantState> assistantGraph(MainGraphFactory factory, BaseCheckpointSaver saver) throws GraphStateException {
        return factory.compile(saver);
    }

    @Bean
    public Clock workflowClock(GraphProperties properties, Environment environment) {
        if ("stub".equals(properties.mode()) && (!environment.acceptsProfiles(
                Profiles.of("graph-stub", "test")) || environment.acceptsProfiles(Profiles.of("prod", "production")))) {
            throw new IllegalArgumentException("Stub graph requires an explicit nonproduction profile");
        }
        return Clock.systemUTC();
    }
}
