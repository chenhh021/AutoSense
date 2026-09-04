package com.chh.autosense;

import com.chh.autosense.integration.AbstractIntegrationIT;
import org.junit.jupiter.api.Test;

/**
 * 应用上下文冒烟测试:需要 Docker(Testcontainers),默认被 surefire 排除,
 * 通过 ./mvnw verify -Pit 运行。
 */
class AutoSenseApplicationTests extends AbstractIntegrationIT {

    @Test
    void contextLoads() {
    }
}
