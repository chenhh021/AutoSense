package com.chh.autosense.ai.model;
import java.util.List;
public record DeviceInfoResult(List<DeviceInfoItem> devices, String errorCode) {
    public DeviceInfoResult { devices = List.copyOf(devices); }
}
