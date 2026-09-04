package com.chh.autosense.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 受支持设备类型注册表(FR-012):yaml 驱动,新增设备类型 = 注册条目 + Adapter Bean。
 * 2026-08-27 起含 deviceSimulator 型号映射与状态→故障判定规则(R12)。
 */
@ConfigurationProperties(prefix = "autosense")
public record DeviceTypeRegistryProperties(Map<String, DeviceTypeSpec> deviceTypes) {

    public record DeviceTypeSpec(
            String displayName,
            boolean supported,
            String simulatorTypeCode,
            Map<String, ModelSpec> models,
            List<FaultRuleSpec> faultRules
    ) {
    }

    public record ModelSpec(
            String displayName,
            List<String> stateFields,
            List<String> commands
    ) {
    }

    /**
     * 故障判定规则:条件(简单比较表达式,作用于设备 state + running_status)→ 结论。
     * conclusion: AUTO_REPAIRABLE / MANUAL_ONLY / AFTERSALES;无命中 = NORMAL。
     */
    public record FaultRuleSpec(
            String name,
            String when,
            String conclusion,
            String action,
            Map<String, Object> params,
            String knowledgeRef,
            String reason
    ) {
    }

    public boolean isSupported(String deviceType) {
        DeviceTypeSpec spec = deviceTypes == null ? null : deviceTypes.get(deviceType);
        return spec != null && spec.supported();
    }

    public DeviceTypeSpec specOf(String deviceType) {
        return deviceTypes == null ? null : deviceTypes.get(deviceType);
    }

    /** 型号是否属于该类型注册表(FR-020 登记校验)。 */
    public boolean isModelSupported(String deviceType, String modelCode) {
        DeviceTypeSpec spec = specOf(deviceType);
        return spec != null && spec.models() != null && spec.models().containsKey(modelCode);
    }

    /**
     * 将模拟器事实字段反向解析为平台诊断键，例如 LITE + LA001 -> smart_bulb。
     * 未注册或型号不匹配返回 empty；调用方仍可保存并展示该设备。
     */
    public Optional<String> diagnosticTypeOf(String simulatorTypeCode, String modelCode) {
        if (modelCode == null) {
            return Optional.empty();
        }
        return diagnosticTypeForSimulatorType(simulatorTypeCode)
                .filter(key -> {
                    DeviceTypeSpec spec = deviceTypes.get(key);
                    return spec.models() != null && spec.models().containsKey(modelCode);
                });
    }

    /** 仅按模拟器类型解析平台诊断键，用于在未知型号设备中先定位候选再明确拒绝。 */
    public Optional<String> diagnosticTypeForSimulatorType(String simulatorTypeCode) {
        if (deviceTypes == null || simulatorTypeCode == null) {
            return Optional.empty();
        }
        return deviceTypes.entrySet().stream()
                .filter(entry -> simulatorTypeCode.equals(entry.getValue().simulatorTypeCode()))
                .map(Map.Entry::getKey)
                .findFirst();
    }
}
