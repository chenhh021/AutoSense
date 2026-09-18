package com.chh.autosense.integration;

import com.chh.autosense.core.security.AuthUser;
import com.chh.autosense.core.session.*;
import com.chh.autosense.domain.dto.*;
import com.chh.autosense.domain.message.WorkflowEvent;
import com.chh.autosense.graph.WorkflowEventStream;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Real simulator, real graph and AI Service proxies with explicit mock model transport. */
@Tag("device-simulator")
@TestPropertySource(properties = "autosense.graph.mode=real")
class AutoRepairFlowIT extends AbstractIntegrationIT {
    @Autowired WorkflowExecutionService executions;
    @Autowired ConversationQueryService conversations;
    final AuthUser user = new AuthUser(1L);

    @Test void queryControlAndVerificationEachRequireApproval() throws Exception {
        var device = createSimulatorDevice("LA001", "graph-repair"); long remote = device.path("id").asLong();
        String name = "图控制灯" + remote;
        assertThat(bindDevice("user-1", device.path("sn").asText(), name).getStatusCode().value()).isEqualTo(201);
        simulatorCommand(remote, "set_brightness", Map.of("brightness", 3));
        var first = consume(executions.create(user, null, "查询" + name + "亮度，低于30就调到80，最后复检"));
        var waiting = first.getLast();
        assertThat(waiting.type()).isEqualTo("CONFIRM");
        var second = approve(waiting, true);
        assertThat(second).anyMatch(e -> e.type().equals("STEP_RESULT") && e.data().stepId().equals("s1"));
        assertThat(second.getLast().type()).isEqualTo("CONFIRM");
        assertThat(simulatorState(remote).path("brightness").asInt()).isEqualTo(3);
        var third = approve(second.getLast(), true);
        assertThat(third.getLast().type()).isEqualTo("CONFIRM");
        assertThat(simulatorState(remote).path("brightness").asInt()).isEqualTo(80);
        var declined = approve(third.getLast(), false);
        assertThat(declined.getLast().code()).isEqualTo("APPROVAL_REJECTED");
        var view = conversations.workflow(user, waiting.data().conversationId(), waiting.data().requestId());
        assertThat(view.steps().get(1).resultCertainty().name()).isEqualTo("SUCCEEDED");
        assertThat(view.steps().get(1).result()).containsEntry("verification", "NOT_PERFORMED");
        assertThat(view.steps().get(2).failureCode()).isEqualTo("APPROVAL_REJECTED");
    }

    @Test void refusingFirstQueryStopsPlanWithoutChangingDevice() throws Exception {
        var device = createSimulatorDevice("LA001", "graph-reject"); long remote = device.path("id").asLong();
        String name = "图拒绝灯" + remote;
        assertThat(bindDevice("user-1", device.path("sn").asText(), name).getStatusCode().value()).isEqualTo(201);
        simulatorCommand(remote, "set_brightness", Map.of("brightness", 4));
        var first = consume(executions.create(user, null, "查询" + name + "亮度，低于30就调到80"));
        var failed = approve(first.getLast(), false);
        assertThat(failed.getLast().code()).isEqualTo("APPROVAL_REJECTED");
        assertThat(simulatorState(remote).path("brightness").asInt()).isEqualTo(4);
    }

    List<WorkflowEvent> approve(WorkflowEvent event, boolean decision) throws Exception {
        var view = conversations.workflow(user, event.data().conversationId(), event.data().requestId());
        return consume(executions.approval(user, view.conversationId(), view.requestId(), new WorkflowApprovalRequest(
                view.approval().stepId(), view.approval().approvalId(), decision, view.version())));
    }
    List<WorkflowEvent> consume(WorkflowExecutionService.Run run) {
        var events = new ArrayList<WorkflowEvent>(); try (run) { new WorkflowEventStream().consume(run.stream(), events::add); } return events;
    }
}
