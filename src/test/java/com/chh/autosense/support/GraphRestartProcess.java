package com.chh.autosense.support;

import com.chh.autosense.AutoSenseApplication;
import com.chh.autosense.graph.checkpoint.MyBatisCheckpointSaver;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import java.util.*;

/** Child-JVM-only crash fixture. This class is never packaged in the application artifact. */
public final class GraphRestartProcess {
    public static void main(String[] args) {
        new SpringApplicationBuilder(AutoSenseApplication.class, CrashConfiguration.class).profiles("graph-stub").run(args);
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class CrashConfiguration {
        @Bean @Primary
        BaseCheckpointSaver crashCheckpointSaver(MyBatisCheckpointSaver delegate, Environment environment) {
            String boundary = environment.getProperty("validation.crash-node", "");
            return new BaseCheckpointSaver() {
                public Collection<Checkpoint> list(RunnableConfig c) { return delegate.list(c); }
                public Optional<Checkpoint> get(RunnableConfig c) { return delegate.get(c); }
                public Tag release(RunnableConfig c) throws Exception { return delegate.release(c); }
                public RunnableConfig put(RunnableConfig c, Checkpoint checkpoint) throws Exception {
                    if (!boundary.isBlank() && checkpoint.getNodeId().contains(boundary)) Runtime.getRuntime().halt(73);
                    return delegate.put(c, checkpoint);
                }
            };
        }
    }
}
