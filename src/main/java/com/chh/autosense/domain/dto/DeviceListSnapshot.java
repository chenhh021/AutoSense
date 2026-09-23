package com.chh.autosense.domain.dto;
import com.chh.autosense.domain.vo.DeviceView;
import java.time.Instant;
import java.util.*;
public record DeviceListSnapshot(List<DeviceView> devices, Map<String, Status> statusMetadata, Instant observedAt) {
    public DeviceListSnapshot { devices = List.copyOf(devices); statusMetadata = Map.copyOf(statusMetadata); }
    public record Status(String source, Instant observedAt, String errorCode, String reason) { }
}
