package com.chh.autosense.unit;

import org.junit.jupiter.api.Test;
import com.chh.autosense.config.AssistantProperties;
import com.chh.autosense.config.LlmProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class LlmConfigurationTest {
    private final ApplicationContextRunner properties = new ApplicationContextRunner()
            .withUserConfiguration(PropertyConfiguration.class)
            .withPropertyValues("autosense.llm.base-url=http://localhost:12345/v1", "autosense.llm.api-key=test-only",
                    "autosense.llm.model-name=test-model", "autosense.llm.temperature=0.2",
                    "autosense.llm.timeout-seconds=30");

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({LlmProperties.class, AssistantProperties.class})
    static class PropertyConfiguration {
        @Bean Object checkedBudget(AssistantProperties assistant, LlmProperties llm) {
            assistant.validateModelBudget(llm);
            return new Object();
        }
    }

    @Test void propertiesBindDefaultsWithoutNetworkCalls() {
        properties.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(LlmProperties.class).maxRetries()).isZero();
            assertThat(context.getBean(AssistantProperties.class).processingTimeoutSeconds()).isEqualTo(120);
            assertThat(context.getBean(AssistantProperties.class).sessionLeaseSeconds()).isEqualTo(30);
            assertThat(context.getBean(AssistantProperties.class).sessionRenewSeconds()).isEqualTo(10);
        });
    }

    @Test void invalidLocalConfigurationFailsAtStartup() {
        for (String invalid : new String[]{"autosense.llm.mode=unknown", "autosense.llm.api-key=",
                "autosense.llm.base-url=invalid", "autosense.llm.model-name=", "autosense.llm.temperature=NaN",
                "autosense.llm.timeout-seconds=0", "autosense.llm.max-retries=-1",
                "autosense.llm.max-retries=4",
                "autosense.assistant.session-renew-seconds=30", "autosense.assistant.context-ttl-seconds=0"}) {
            properties.withPropertyValues(invalid).run(context -> assertThat(context).hasFailed());
        }
        properties.withPropertyValues("autosense.llm.mode=mock", "autosense.llm.api-key=",
                "autosense.llm.base-url=").run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void defaultModeSelectsRealLlmBeans() throws IOException {
        var yamlSources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yaml"));
        var propertySources = new MutablePropertySources();
        yamlSources.forEach(propertySources::addLast);

        var resolver = new PropertySourcesPropertyResolver(propertySources);

        assertThat(resolver.getProperty("autosense.llm.mode")).isEqualTo("real");
    }
}
