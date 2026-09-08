package com.chh.autosense.service.device;

import com.chh.autosense.config.DeviceTypeRegistryProperties;
import com.chh.autosense.core.device.spi.DeviceAdapter;
import com.chh.autosense.domain.entity.Device;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 设备适配器注册表(FR-012):按设备类型定位 Adapter Bean;
 * 类型是否受支持以 yaml 注册表为准。
 */
@Component
public class DeviceAdapterRegistryService {

    private final Map<String, DeviceAdapter> adapters;
    private final DeviceTypeRegistryProperties registryProperties;

    public DeviceAdapterRegistryService(List<DeviceAdapter> adapterList,
                                        DeviceTypeRegistryProperties registryProperties) {
        this.adapters = adapterList.stream()
                .collect(Collectors.toMap(DeviceAdapter::deviceType, Function.identity()));
        this.registryProperties = registryProperties;
    }

    public boolean isSupported(String deviceType) {
        return registryProperties.isSupported(deviceType) && adapters.containsKey(deviceType);
    }

    public Optional<DeviceAdapter> adapterOf(String deviceType) {
        return Optional.ofNullable(adapters.get(deviceType));
    }

    public DeviceTypeRegistryProperties.DeviceTypeSpec specOf(String deviceType) {
        return registryProperties.specOf(deviceType);
    }

    public Optional<String> diagnosticTypeOf(String simulatorTypeCode, String modelCode) {
        return registryProperties.diagnosticTypeOf(simulatorTypeCode, modelCode);
    }

    public Optional<String> diagnosticTypeForSimulatorType(String simulatorTypeCode) {
        return registryProperties.diagnosticTypeForSimulatorType(simulatorTypeCode);
    }

    public Optional<String> diagnosticTypeOf(Device device) {
        return device == null ? Optional.empty()
                : diagnosticTypeOf(device.getDeviceTypeCode(), device.getDeviceModelCode());
    }

    public boolean isSupported(String simulatorTypeCode, String modelCode) {
        return diagnosticTypeOf(simulatorTypeCode, modelCode)
                .filter(registryProperties::isSupported)
                .filter(adapters::containsKey)
                .isPresent();
    }

    public boolean isSupported(Device device) {
        return device != null && isSupported(
                device.getDeviceTypeCode(), device.getDeviceModelCode());
    }

    public Optional<DeviceAdapter> adapterOf(Device device) {
        return diagnosticTypeOf(device).flatMap(this::adapterOf);
    }
}
