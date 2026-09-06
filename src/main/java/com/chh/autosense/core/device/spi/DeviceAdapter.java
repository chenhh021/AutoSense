package com.chh.autosense.core.device.spi;

import com.chh.autosense.domain.entity.Device;

import java.util.List;
import java.util.Map;

/**
 * 设备适配器 SPI(FR-012,research R5):每类设备一个实现 Bean,
 * 诊断项与修复动作白名单由 yaml 注册表声明;修复动作只能来自白名单。
 */
public interface DeviceAdapter {

    /** 与注册表一致的设备类型 code(如 router / air_conditioner / smart_bulb) */
    String deviceType();

    /** 拉取基础诊断信息(FR-005);设备不可达时抛 DeviceUnreachableException */
    Map<String, Object> getDiagnostics(Device device);

    /** 执行白名单修复动作(FR-008);result=false 表示执行失败(失败即停,FR-017) */
    RepairOutcome executeRepair(Device device, String actionCode, Map<String, Object> params);

    /** 该类型允许的修复动作码白名单 */
    List<String> supportedRepairActions();

    record RepairOutcome(boolean success, String message) {
    }
}
