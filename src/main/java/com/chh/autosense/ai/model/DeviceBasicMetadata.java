package com.chh.autosense.ai.model;
import java.util.List;
public record DeviceBasicMetadata(String firmwareVersion, String source, List<String> missingFields) {
    public DeviceBasicMetadata { missingFields = List.copyOf(missingFields); }
}
