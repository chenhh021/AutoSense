package com.chh.autosense.contract;

import org.junit.jupiter.api.Test;
import dev.langchain4j.data.message.UserMessage;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KnowledgeRetrievalContractTest extends KnowledgeCommonSenseContractTest {
    @Test void actualRagRetrievesBothModelsAndPersistsAllDisplayedSources() throws Exception {
        route(true);
        String response = post("", "{\"problem\":\"智能灯泡最大功率\"}");
        assertThat(response).contains("event:conclusion", "没有当前型号设备信息", "MI-MJDPL01YL", "ACME-L2", "12 W", "220 V")
                .doesNotContain("event:awaiting", "event:error");
        assertThat(probe.requests).hasSize(2); assertThat(probe.direct).isEmpty();
        var input = json.readTree(((UserMessage) probe.requests.getLast().messages().getLast()).singleText());
        assertThat(input.path("evidence")).hasSize(2);
        assertThat(input.path("scope").path("deviceType").asText()).isEqualTo("light");
        assertThat(input.has("deadlineEpochMillis")).isFalse();
        verify(store, times(1)).search(any()); assertThat(embedding.queryCalls).hasValue(1);
        verifyNoInteractions(devices);
        verify(processing).finish(eq(accepted), any(), contains("document/light/ACME-L2/general.md"),
                argThat(c -> c.summary().contains("document/light/MI-MJDPL01YL/general.md")), isNull());
    }
    @Test void unknownAndEmptyTypeAreDistinctFromRetrievalFailure() throws Exception {
        route(true); probe.type = null;
        assertThat(post("", "{\"problem\":\"功能参数\"}")).contains("设备类型", "event:conclusion").doesNotContain("未检索到");
        verify(store, never()).search(any());
        probe.type = "air";
        assertThat(post("", "{\"problem\":\"空调参数\"}")).contains("未检索到足够相关的资料", "event:conclusion");
        verify(store, times(1)).search(any());
        doThrow(new IllegalStateException("private-backend-detail")).when(store).search(any());
        int directCount = probe.direct.size();
        try {
            assertThat(post("", "{\"problem\":\"空调参数\"}")).contains("event:error").doesNotContain("event:conclusion", "private-backend-detail");
            assertThat(probe.direct).hasSize(directCount);
        } finally { reset(store); }
    }
    @Test void forgedSourceNeverEmitsEnhancedToken() throws Exception {
        route(true); probe.invalidSource = true;
        assertThat(post("", "{\"problem\":\"智能灯泡参数\"}")).contains("event:error").doesNotContain("event:token", "event:conclusion", "fabricated");
        assertThat(probe.direct).isEmpty(); verifyNoInteractions(devices);
    }
}
