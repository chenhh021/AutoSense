package com.chh.autosense.integration;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class WorkflowOpenApiIT extends AbstractIntegrationIT {
    @Test void exportsLiveOpenApiForFrontendGeneration() throws Exception {
        var response = restTemplate.getForEntity(url("/v3/api-docs"), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("WorkflowApprovalRequest", "WorkflowView", "WorkflowEvent", "expectedVersion", "inputRequestId", "/resume", "/cancel");
        java.nio.file.Files.writeString(java.nio.file.Path.of("target", "workflow-openapi.json"), response.getBody(), java.nio.charset.StandardCharsets.UTF_8);
    }
}
