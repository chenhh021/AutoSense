package com.chh.autosense.service;

import com.chh.autosense.domain.dto.DeviceCapabilityDefinition;

public interface DeviceCapabilityService {
    /** Exact normalized type and model. Never substitutes another model. */
    DeviceCapabilityDefinition get(String deviceType, String deviceModel);
}
