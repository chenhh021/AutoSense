package com.chh.autosense.graph.state;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/** Server-validated execution target. Model output must never populate this context directly. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResolvedDevice(Long deviceRef, String name, String sn, String deviceType, String model,
                             String bindingHash, String capabilityHash, List<String> getScope,
                             String runtimeProvider, String runtimeSource, String runtimeEndpointHash,
                             String snapshotKind) implements Serializable {
    private static final ObjectMapper JSON = new ObjectMapper();

    public ResolvedDevice {
        getScope = getScope == null ? null : List.copyOf(getScope);
    }

    public static ResolvedDevice from(Map<String, Object> data) {
        return data == null || data.isEmpty() ? null : JSON.convertValue(data, ResolvedDevice.class);
    }

    public Map<String, Object> asMap() {
        return StateData.freeze(JSON.convertValue(this, new com.fasterxml.jackson.core.type.TypeReference<>() { }));
    }
}
