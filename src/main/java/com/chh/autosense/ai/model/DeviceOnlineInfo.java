package com.chh.autosense.ai.model;
import java.time.Instant;
import java.util.Map;
public record DeviceOnlineInfo(String source, Instant generatedAt, String capabilityHash,
                               Map<String, DeviceAttributeModels.Attributes> getResults) {
    public DeviceOnlineInfo { getResults = Map.copyOf(getResults); }
}
