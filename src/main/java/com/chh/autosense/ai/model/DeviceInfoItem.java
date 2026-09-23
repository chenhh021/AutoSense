package com.chh.autosense.ai.model;
import java.util.Map;
public record DeviceInfoItem(String requestedSn, DeviceBasicInfo basicInfo, DeviceOnlineInfo onlineInfo,
                             DeviceCapabilityInfo capabilityInfo, String errorCode, Map<String, String> componentErrors) {
    public DeviceInfoItem { componentErrors = Map.copyOf(componentErrors); }
}
