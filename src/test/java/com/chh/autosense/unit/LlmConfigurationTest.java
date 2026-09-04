package com.chh.autosense.unit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class LlmConfigurationTest {

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
