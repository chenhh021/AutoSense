package com.chh.autosense.ai.model;
import java.time.Instant;
import java.util.*;
public record DeviceListResult(List<DeviceBasicInfo> devices, Map<String, DeviceStatusMetadata> statusMetadata,
                               Map<String, DeviceBasicMetadata> basicMetadata, Instant observedAt, String errorCode) {
    public DeviceListResult {
        devices = List.copyOf(devices); statusMetadata = Map.copyOf(statusMetadata); basicMetadata = Map.copyOf(basicMetadata);
    }
}
