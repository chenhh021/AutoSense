package com.chh.autosense.core.session;

import com.chh.autosense.domain.entity.WorkflowCheckpoint;
import com.chh.autosense.graph.checkpoint.AssistantStateSerializer;
import com.chh.autosense.graph.state.AssistantState;
import com.chh.autosense.mapper.WorkflowCheckpointMapper;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.io.IOException;
import java.time.*;
import java.util.*;

@Service
public class WorkflowCheckpointService {
    private final WorkflowCheckpointMapper checkpoints;
    private final WorkflowClaimService claims;
    private final AssistantStateSerializer serializer = new AssistantStateSerializer();
    public WorkflowCheckpointService(WorkflowCheckpointMapper checkpoints, WorkflowClaimService claims) {
        this.checkpoints = checkpoints; this.claims = claims;
    }

    @Transactional(rollbackFor = Exception.class)
    public String save(String threadId, long fence, String parentId, Checkpoint checkpoint) throws IOException {
        var workflow = claims.requireFence(threadId, fence);
        var state = new AssistantState(checkpoint.getState());
        if (!state.request().requestId().equals(threadId) || state.request().conversationId() != workflow.getSessionId()
                || state.request().userId() != workflow.getUserId() || state.workflow().executionFence() != fence)
            throw new IOException("Checkpoint identity does not match the execution claim");
        var latest = checkpoints.latest(threadId);
        String id = checkpoint.getId();
        if (checkpoints.find(threadId, id) != null) id = UUID.randomUUID().toString();
        var row = new WorkflowCheckpoint();
        row.setThreadId(threadId); row.setCheckpointId(id);
        row.setParentCheckpointId(parentId != null ? parentId : latest == null ? null : latest.getCheckpointId());
        row.setNodeId(checkpoint.getNodeId()); row.setNextNode(checkpoint.getNextNodeId());
        row.setStatePayload(serializer.encode(state.data()));
        row.setSchemaVersion(AssistantStateSerializer.SCHEMA_VERSION); row.setGraphVersion(AssistantStateSerializer.GRAPH_VERSION);
        row.setVersion(latest == null ? 1 : latest.getVersion() + 1); row.setFence(fence);
        row.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC)); checkpoints.insert(row);
        return id;
    }

    @Transactional(readOnly = true)
    public Optional<Checkpoint> get(String threadId, String id) {
        var row = id == null ? checkpoints.latest(threadId) : checkpoints.find(threadId, id);
        return Optional.ofNullable(row).map(this::restore);
    }
    @Transactional(readOnly = true)
    public List<Checkpoint> history(String threadId) { return checkpoints.history(threadId).stream().map(this::restore).toList(); }

    private Checkpoint restore(WorkflowCheckpoint row) {
        if (row.getSchemaVersion() != AssistantStateSerializer.SCHEMA_VERSION
                || !AssistantStateSerializer.GRAPH_VERSION.equals(row.getGraphVersion()))
            throw new IllegalStateException("Unsupported checkpoint version");
        try {
            var data = serializer.decode(row.getStatePayload());
            if (!new AssistantState(data).request().requestId().equals(row.getThreadId()))
                throw new IOException("Checkpoint thread identity mismatch");
            return Checkpoint.builder().id(row.getCheckpointId()).state(data).nodeId(row.getNodeId()).nextNodeId(row.getNextNode()).build();
        } catch (IOException e) { throw new IllegalStateException("Invalid persisted checkpoint", e); }
    }
}
