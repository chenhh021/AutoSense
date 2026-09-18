package com.chh.autosense.integration;

import com.chh.autosense.core.security.AuthUser;
import com.chh.autosense.core.session.ConversationQueryService;
import com.chh.autosense.support.LogCaptureSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class WorkflowLoggingIT extends AbstractWorkflowIT {
    @Autowired ConversationQueryService conversations;
    @Autowired org.springframework.context.ApplicationContext context;

    @Test void concurrentHttpStreamsKeepWorkflowIdentityAndRedactBodies() throws Exception {
        try (var logs = new LogCaptureSupport(); var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> post("", Map.of("problem", "什么是色温 private-input-one")));
            var second = workers.submit(() -> post("", Map.of("problem", "什么是亮度 private-input-two")));
            for (var call : List.of(first, second)) assertThat(call.get(30, TimeUnit.SECONDS)).contains("event:conclusion");
            var events = logs.events().stream().filter(e -> e.getMessage().getFormattedMessage().startsWith("Workflow event committed")
                    && e.getContextData().getValue("workflowRequestId") != null).toList();
            assertThat(events).isNotEmpty();
            var identities = new HashSet<String>();
            for (var event : events) {
                String id = event.getContextData().getValue("workflowRequestId"); identities.add(id);
                var row = workflows.selectOneById(id);
                assertThat(event.getContextData().<String>getValue("sessionId")).isEqualTo(row.getSessionId().toString());
                assertThat(event.getContextData().<String>getValue("userId")).isEqualTo("1");
                assertThat(event.getContextData().<String>getValue("requestId")).isNotBlank();
                assertThat(event.getLevel()).isEqualTo(org.apache.logging.log4j.Level.INFO);
            }
            assertThat(identities).hasSize(2);
            assertThat(logs.rendered()).doesNotContain("private-input-", "Bearer user-1", "{{history}}");
        }
    }

    @Test void approvalTransportHasNewRequestIdButRetainsWorkflowIdentityAndSingleEngine() throws Exception {
        String body = post("", Map.of("problem", "查询状态"));
        var mapper = new ObjectMapper();
        var last = body.lines().filter(line -> line.startsWith("data:") && line.contains("\"conversationId\""))
                .map(line -> line.substring(5)).reduce((a, b) -> b).orElseThrow();
        var data = mapper.readTree(last).path("data");
        String id = data.path("requestId").asText(); long session = data.path("conversationId").asLong();
        var view = conversations.workflow(new AuthUser(1L), session, id);
        try (var logs = new LogCaptureSupport()) {
            assertThat(post("/" + session + "/workflows/" + id + "/approval", Map.of("stepId", view.approval().stepId(),
                    "approvalId", view.approval().approvalId(), "approved", true, "expectedVersion", view.version())))
                    .contains("event:conclusion");
            var events = logs.events().stream().filter(e -> id.equals(e.getContextData().getValue("workflowRequestId"))).toList();
            assertThat(events).isNotEmpty();
            for (var event : events) assertThat(event.getContextData().<String>getValue("requestId")).isNotBlank().isNotEqualTo(id);
        }
        assertThat(context.getBeansOfType(org.bsc.langgraph4j.CompiledGraph.class)).hasSize(1);
        assertThat(context.getBean(org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver.class))
                .isInstanceOf(com.chh.autosense.graph.checkpoint.MyBatisCheckpointSaver.class);
        assertThat(Arrays.asList(context.getBeanDefinitionNames())).doesNotContain("sessionOrchestrator", "sessionProcessingService", "capabilityDispatcher");
    }

    private String post(String suffix, Map<String, Object> input) {
        var response = restTemplate.exchange(url("/api/v1/sessions" + suffix), HttpMethod.POST,
                new HttpEntity<>(input, authHeaders("user-1")), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200); return response.getBody();
    }
}
