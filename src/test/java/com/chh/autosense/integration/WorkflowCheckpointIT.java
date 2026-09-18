package com.chh.autosense.integration;

import com.chh.autosense.core.session.WorkflowClaimService;
import com.chh.autosense.domain.entity.WorkflowExecution;
import com.chh.autosense.graph.checkpoint.MyBatisCheckpointSaver;
import com.chh.autosense.graph.state.AssistantState;
import com.chh.autosense.mapper.WorkflowExecutionMapper;
import org.bsc.langgraph4j.*;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class WorkflowCheckpointIT extends AbstractIntegrationIT {
    @Autowired MyBatisCheckpointSaver saver;
    @Autowired WorkflowClaimService claims;
    @Autowired WorkflowExecutionMapper workflows;

    @Test void persistsLatestAndSpecifiedSnapshotsAndRejectsAnOldExecutionFence() throws Exception {
        String id = UUID.randomUUID().toString();
        var workflow = new WorkflowExecution();
        workflow.setRequestId(id); workflow.setUserId(1L); workflow.setSessionId(7L); workflow.setReportId(8L);
        workflow.setOriginMessageId(9L); workflow.setLatestInputMessageId(9L); workflow.setGraphVersion("assistant-v1");
        workflow.setSchemaVersion(1); workflow.setStatus("CREATED"); workflow.setVersion(0L); workflow.setFence(0L);
        workflows.insertSelective(workflow);
        var claim = claims.acquire(id, 1, 0);
        var config = RunnableConfig.builder().threadId(id).build().updateMetadata(Map.of(MyBatisCheckpointSaver.EXECUTION_FENCE, claim.fence()));
        var data = new LinkedHashMap<>(AssistantState.initial(new AssistantState.RequestContext(id, 7, 1, "original")));
        var w = new AssistantState(data).workflow();
        data.put(AssistantState.WORKFLOW, new AssistantState.WorkflowContext(w.status(), 0, w.progress(), 0, "", "", "", "", 1,
                "assistant-v1", 0, claim.fence()));
        var first = saver.put(config, Checkpoint.builder().id("first").state(data).nodeId("PrepareApproval").nextNodeId("AwaitApproval").build());
        var second = saver.put(config, Checkpoint.builder().id("second").state(data).nodeId("CompleteStep").nextNodeId("PlanRouter").build());
        assertThat(saver.get(config).orElseThrow().getId()).isEqualTo(second.checkPointId().orElseThrow());
        assertThat(saver.get(first).orElseThrow().getNextNodeId()).isEqualTo("AwaitApproval");
        assertThat(saver.list(config)).extracting(Checkpoint::getId).containsExactly("second", "first");
        saver.release(config);
        assertThat(saver.list(config)).hasSize(2);
        claims.release(claim);
        assertThatThrownBy(() -> saver.put(config, Checkpoint.builder().id("late").state(data).build()))
                .isInstanceOf(RuntimeException.class);
        assertThat(saver.list(config)).hasSize(2);
    }
}
