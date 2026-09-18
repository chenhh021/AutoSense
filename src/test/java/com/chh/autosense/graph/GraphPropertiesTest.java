package com.chh.autosense.graph;

import com.chh.autosense.config.GraphProperties;
import com.chh.autosense.config.GraphConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class GraphPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test void defaultBudgetsAreFiniteAndRealModeIsDefault() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(GraphProperties.class);
            assertThat(properties.mode()).isEqualTo("real");
            assertThat(properties.maxRetries()).isEqualTo(2);
            assertThat(properties.stepTimeout("DEVICE_CONTROL").toSeconds()).isEqualTo(10);
            assertThat(properties.stepTimeout("KNOWLEDGE_CONSULT").toSeconds()).isEqualTo(60);
            assertThat(properties.approvalTtlSeconds()).isEqualTo(300);
        });
    }

    @Test void overridesOnlyNamedBudgetsAndRetainsOtherDefaults() {
        runner.withPropertyValues("autosense.graph.step-timeout-seconds[DEVICE_QUERY]=7")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var properties = context.getBean(GraphProperties.class);
                    assertThat(properties.stepTimeout("DEVICE_QUERY").toSeconds()).isEqualTo(7);
                    assertThat(properties.stepTimeout("DEVICE_CONTROL").toSeconds()).isEqualTo(10);
                });
    }

    @Test void invalidBudgetsFailBinding() {
        for (String value : new String[]{"max-retries=-1", "mode=automatic",
                "approval-ttl-seconds=0", "step-timeout-seconds[EXECUTE_SCRIPT]=1"}) {
            runner.withPropertyValues("autosense.graph." + value)
                    .run(context -> assertThat(context).hasFailed());
        }
    }

    @Test void stubRequiresExplicitNonproductionProfile() {
        runner.withUserConfiguration(GraphConfiguration.class)
                .withPropertyValues("autosense.graph.mode=stub")
                .run(context -> assertThat(context).hasFailed());
        runner.withUserConfiguration(GraphConfiguration.class)
                .withPropertyValues("autosense.graph.mode=stub", "spring.profiles.active=graph-stub")
                .run(context -> assertThat(context).hasNotFailed());
        runner.withUserConfiguration(GraphConfiguration.class)
                .withPropertyValues("autosense.graph.mode=stub", "spring.profiles.active=graph-stub,prod")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(GraphProperties.class)
    static class PropertiesConfiguration { }
}
