package com.chh.autosense.graph.checkpoint;

import com.chh.autosense.core.session.WorkflowCheckpointService;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.*;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public final class MyBatisCheckpointSaver implements BaseCheckpointSaver {
    public static final String EXECUTION_FENCE = "autosense.executionFence";
    private final WorkflowCheckpointService service;
    public MyBatisCheckpointSaver(WorkflowCheckpointService service) { this.service = service; }
    @Override public String threadId(RunnableConfig config) {
        return config.threadId().filter(id -> !id.isBlank() && id.length() <= 36)
                .orElseThrow(() -> new IllegalArgumentException("A persisted workflow thread is required"));
    }
    @Override public Collection<Checkpoint> list(RunnableConfig config) { return service.history(threadId(config)); }
    @Override public Optional<Checkpoint> get(RunnableConfig config) {
        return service.get(threadId(config), config.checkPointId().orElse(null));
    }
    @Override public RunnableConfig put(RunnableConfig config, Checkpoint checkpoint) throws Exception {
        Object fence = config.metadata(EXECUTION_FENCE).orElseThrow(() -> new IllegalStateException("Missing execution fence"));
        if (!(fence instanceof Number number)) throw new IllegalStateException("Invalid execution fence");
        String id = service.save(threadId(config), number.longValue(), config.checkPointId().orElse(null), checkpoint);
        return RunnableConfig.builder(config).checkPointId(id).build();
    }
    /** releaseThread(false) is mandatory; release never destroys business history. */
    @Override public Tag release(RunnableConfig config) { return new Tag(threadId(config), list(config)); }
}
