package com.chh.autosense.service;

import com.chh.autosense.ai.model.DeviceOnlineInfo;
import com.chh.autosense.domain.dto.DeviceCapabilityDefinition;
import java.time.Instant;

public interface DeviceOnlineInfoService {
    /** SN is trusted routing input; mock values still depend only on workflow/step/profile. */
    record Invocation(String workflowId, String stepId, Instant generatedAt, boolean online, String sn, Instant deadline) {
        public Invocation(String workflowId, String stepId, Instant generatedAt, boolean online) {
            this(workflowId, stepId, generatedAt, online, null, null);
        }
        public Invocation {
            if (workflowId == null || workflowId.isBlank() || stepId == null || stepId.isBlank() || generatedAt == null)
                throw new IllegalArgumentException("Missing runtime invocation identity");
        }
    }
    record RuntimeIdentity(String provider, String source, String endpointHash) { }
    RuntimeIdentity runtimeIdentity();
    DeviceOnlineInfo read(DeviceCapabilityDefinition definition, Invocation invocation);
    /** Validate structured model compatibility before requesting full-snapshot approval. */
    void validateModel(DeviceCapabilityDefinition definition);
}
