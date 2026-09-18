package com.chh.autosense.contract;

import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KnowledgeRetrievalContractTest extends KnowledgeCommonSenseContractTest {
    @Test void actualRagRetrievesBothModelsAndReturnsEveryDisplayedSourceForPersistence() throws Exception {
        String response = answer(true, "智能灯泡最大功率", List.of());
        assertThat(response).contains("没有当前型号设备信息", "MI-MJDPL01YL", "ACME-L2", "12 W", "220 V");
        assertThat(probe.requests).hasSize(2); assertThat(probe.direct).isEmpty();
        var input = json.readTree(((UserMessage) probe.requests.getLast().messages().getLast()).singleText());
        assertThat(input.path("evidence")).hasSize(2);
        assertThat(input.path("scope").path("deviceType").asText()).isEqualTo("light");
        assertThat(input.has("deadlineEpochMillis")).isFalse();
        verify(store, times(1)).search(any()); assertThat(embedding.queryCalls).hasValue(1);
        assertThat((List<?>) answerData.get("sources")).hasSize(2);
        assertThat(answerData.get("answer").toString()).contains("document/light/ACME-L2/general.md", "document/light/MI-MJDPL01YL/general.md");
    }
    @Test void unknownTypeAndNoMatchFallbackButTechnicalFailureDoesNot() throws Exception {
        probe.type = null;
        assertThat(answer(true, "功能参数", List.of())).contains("设备类型").doesNotContain("未检索到");
        verify(store, never()).search(any());
        probe.type = "air";
        assertThat(answer(true, "空调参数", List.of())).contains("未检索到足够相关的资料");
        verify(store, times(1)).search(any());
        doThrow(new IllegalStateException("private-backend-detail")).when(store).search(any());
        int directCount = probe.direct.size();
        assertThatThrownBy(() -> answer(true, "空调参数", List.of())).isInstanceOf(RuntimeException.class);
        assertThat(probe.direct).hasSize(directCount);
    }
    @Test void forgedSourceNeverBecomesAnAnswerOrTriggersDirectFallback() {
        probe.invalidSource = true;
        assertThatThrownBy(() -> answer(true, "智能灯泡参数", List.of())).isInstanceOf(RuntimeException.class);
        assertThat(probe.direct).isEmpty();
    }
}
