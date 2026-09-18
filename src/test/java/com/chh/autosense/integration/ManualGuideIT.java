package com.chh.autosense.integration;

import com.chh.autosense.core.security.AuthUser;
import com.chh.autosense.core.session.*;
import com.chh.autosense.domain.dto.WorkflowApprovalRequest;
import com.chh.autosense.domain.message.WorkflowEvent;
import com.chh.autosense.graph.WorkflowEventStream;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@Tag("device-simulator")
@TestPropertySource(properties = "autosense.graph.mode=real")
class ManualGuideIT extends AbstractIntegrationIT {
    @Autowired WorkflowExecutionService executions;
    @Autowired ConversationQueryService conversations;
    @Autowired JdbcTemplate jdbc;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean com.chh.autosense.core.device.client.DeviceServiceClient client;
    @Test void diagnosisUsesApprovedEvidenceAndProvidesManualGuidanceWithoutCommands() throws Exception {
        var remote = createSimulatorDevice("LA001", "graph-manual"); long id = remote.path("id").asLong();
        String name = "图诊断灯" + id;
        assertThat(bindDevice("user-1", remote.path("sn").asText(), name).getStatusCode().value()).isEqualTo(201);
        simulatorCommand(id, "set_color_temperature", Map.of("color_temperature", 6500));
        org.mockito.Mockito.clearInvocations(client);
        var user = new AuthUser(1L);
        var start = consume(executions.create(user, null, "诊断" + name + "色温异常"));
        assertThat(start.getLast().type()).isEqualTo("CONFIRM");
        org.mockito.Mockito.verifyNoInteractions(client);
        var view = conversations.workflow(user, start.getLast().data().conversationId(), start.getLast().data().requestId());
        var result = consume(executions.approval(user, view.conversationId(), view.requestId(), new WorkflowApprovalRequest(
                view.approval().stepId(), view.approval().approvalId(), true, view.version())));
        assertThat(result.getLast().type()).isEqualTo("CONCLUSION");
        org.mockito.Mockito.verify(client, org.mockito.Mockito.times(1)).getDeviceState(id);
        org.mockito.Mockito.verifyNoMoreInteractions(client);
        var diagnosis = result.stream().filter(e -> e.type().equals("STEP_RESULT") && e.data().stepId().equals("s2")).findFirst().orElseThrow();
        assertThat(diagnosis.data().payload().toString()).contains("MANUAL_ONLY", "manualSteps", "6500", "s1");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM command_execution WHERE request_id=?", Integer.class, view.requestId())).isZero();
        assertThat(simulatorState(id).path("color_temperature").asInt()).isEqualTo(6500);
    }
    @Test void standaloneAfterSalesUsesNoDeviceAndUnknownLocationFallsBackToHotline() throws Exception {
        org.mockito.Mockito.clearInvocations(client);
        var known = consume(executions.create(new AuthUser(1L), null, "杭州售后网点"));
        assertThat(known.getLast().type()).isEqualTo("CONCLUSION");
        assertThat(known.toString()).contains("杭州西湖店");
        var unknown = consume(executions.create(new AuthUser(1L), null, "火星售后网点"));
        assertThat(unknown.toString()).contains("品牌官方客服");
        org.mockito.Mockito.verifyNoInteractions(client);
    }
    List<WorkflowEvent> consume(WorkflowExecutionService.Run run) {
        var result = new ArrayList<WorkflowEvent>(); try (run) { new WorkflowEventStream().consume(run.stream(), result::add); } return result;
    }
}
