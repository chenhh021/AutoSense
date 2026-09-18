package com.chh.autosense.graph;

import org.bsc.langgraph4j.*;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.bsc.langgraph4j.state.AgentState;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;
import static org.assertj.core.api.Assertions.assertThat;

class GraphAssemblyTest {
    @Test void inlineSubgraphInterruptResumesWithSameThreadAndUpdatedState() throws Exception {
        var reads = new ArrayList<String>();
        var child = new StateGraph<AgentState>(AgentState::new)
                .addNode("PrepareApproval", node_async(s -> Map.of("waiting", true)))
                .addNode("AwaitApproval", node_async(s -> {
                    if (!s.<Boolean>value("approved").orElse(false)) throw new IllegalStateException("Missing approval");
                    return Map.of("waiting", false);
                }))
                .addNode("Read", node_async(s -> { reads.add("read"); return Map.of("value", 20); }))
                .addEdge(StateGraph.START, "PrepareApproval")
                .addEdge("PrepareApproval", "AwaitApproval")
                .addEdge("AwaitApproval", "Read").addEdge("Read", StateGraph.END);
        var saver = new MemorySaver();
        var graph = new StateGraph<AgentState>(AgentState::new)
                .addNode("QuerySubGraph", child)
                .addEdge(StateGraph.START, "QuerySubGraph").addEdge("QuerySubGraph", StateGraph.END)
                .compile(CompileConfig.builder().checkpointSaver(saver).releaseThread(false)
                        .interruptBefore(SubGraphNode.formatId("QuerySubGraph", "AwaitApproval")).build());
        var config = RunnableConfig.builder().threadId("request-1").build();
        List<String> nodes = new ArrayList<>();
        for (var output : graph.stream(GraphInput.args(Map.of("identity", "user-1")), config)) nodes.add(output.node());
        assertThat(reads).isEmpty();
        assertThat(saver.get(config)).isPresent();
        var updated = graph.updateState(config, Map.of("approved", true));
        assertThat(updated.threadId()).contains("request-1");
        for (var output : graph.stream(GraphInput.resume(), updated)) nodes.add(output.node());
        assertThat(reads).containsExactly("read");
        // updateState returns a checkpoint-specific config. Read the latest snapshot separately.
        assertThat(graph.getState(config).state().<Integer>value("value")).contains(20);
        assertThat(saver.list(config)).isNotEmpty();
    }
}
