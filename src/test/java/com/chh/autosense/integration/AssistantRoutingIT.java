package com.chh.autosense.integration;

import com.chh.autosense.core.device.client.DeviceServiceClient;
import com.chh.autosense.domain.enums.AssistantCapability;
import com.chh.autosense.support.RecordingCapabilityConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Import(RecordingCapabilityConfiguration.class)
class AssistantRoutingIT extends AbstractIntegrationIT {
    @Autowired RecordingCapabilityConfiguration.Recorder recorder;
    @MockitoSpyBean DeviceServiceClient deviceClient;
    private String token;
    private long userId;

    @BeforeEach void authenticate() {
        recorder.reset(); clearInvocations(deviceClient);
        String account = "it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        var registration = restTemplate.postForEntity(url("/api/v1/users/register"), Map.of(
                "userAccount", account, "userPassword", "testPass123", "confirmPassword", "testPass123"), JsonNode.class);
        assertThat(registration.getStatusCode().value()).isEqualTo(201);
        userId = registration.getBody().get("id").asLong();
        var login = restTemplate.postForEntity(url("/api/v1/users/login"), Map.of(
                "userAccount", account, "userPassword", "testPass123"), JsonNode.class);
        assertThat(login.getStatusCode().value()).isEqualTo(200);
        token = login.getBody().get("token").asText();
    }

    @Test void allFourRoutesReceiveAuthenticatedContextExactlyOnceAndNeverTouchDevices() {
        List<String> questions = List.of("什么是智能灯泡", "列出我的所有设备", "客厅灯为什么不亮", "请打开客厅灯");
        for (int i = 0; i < questions.size(); i++) {
            String response = post("", Map.of("problem", questions.get(i)));
            assertThat(response).contains("event:conclusion", "test receiver").doesNotContain("event:error");
            var call = recorder.calls.get(i);
            assertThat(call.user().userId()).isEqualTo(userId);
            assertThat(call.capability()).isEqualTo(AssistantCapability.values()[i]);
            assertThat(call.round()).isEqualTo(1);
            assertThat(call.messageId()).isPositive();
            assertThat(call.history().messages()).isEmpty();
            assertThat(call.content()).isEqualTo(questions.get(i));
        }
        assertThat(recorder.calls).hasSize(4);
        verifyNoInteractions(deviceClient);
    }

    @Test void terminalSessionReclassifiesANewRoundWithOneCurrentInput() {
        String first = post("", Map.of("problem", "什么是智能灯泡"));
        var match = Pattern.compile("\"sessionId\":(\\d+)").matcher(first);
        assertThat(match.find()).isTrue();
        long sessionId = Long.parseLong(match.group(1));
        String next = post("/" + sessionId + "/messages", Map.of("content", "列出我的所有设备"));
        assertThat(next).contains("event:conclusion");
        assertThat(recorder.calls).hasSize(2);
        var call = recorder.calls.get(1);
        assertThat(call.sessionId()).isEqualTo(sessionId);
        assertThat(call.round()).isEqualTo(2);
        assertThat(call.capability()).isEqualTo(AssistantCapability.DEVICE_QUERY);
        assertThat(call.history().messages()).hasSize(2);
        assertThat(call.history().messages()).noneMatch(m -> m.content().equals(call.content()));
        verifyNoInteractions(deviceClient);
    }

    @Test void ambiguousCompoundAndOutOfScopeRequestsNeverDispatchOrAccessDevices() {
        for (String question : List.of("帮我看看", "查亮度并调到80", "如果亮度低就打开灯", "打开所有灯")) {
            assertThat(post("", Map.of("problem", question))).contains("event:awaiting");
        }
        assertThat(post("", Map.of("problem", "写一首诗"))).contains("event:conclusion");
        assertThat(recorder.calls).isEmpty();
        verifyNoInteractions(deviceClient);
    }

    private String post(String suffix, Object body) {
        var result = restTemplate.exchange(url("/api/v1/sessions" + suffix), HttpMethod.POST,
                new HttpEntity<>(body, authHeaders(token)), String.class);
        assertThat(result.getStatusCode().value()).isEqualTo(200);
        return result.getBody();
    }
}
