package com.chh.autosense.core.device.adapter;

import com.chh.autosense.config.DeviceTypeRegistryProperties;
import com.chh.autosense.core.device.client.DeviceServiceClient;
import com.chh.autosense.core.device.spi.DeviceAdapter;
import com.chh.autosense.domain.entity.Device;

import java.util.List;
import java.util.Map;

/**
 * 通用设备适配器基类(R11,2026-08-27 修订):诊断/命令均经 DeviceServiceClient
 * 调用 deviceSimulator;动作白名单来自 yaml 注册表型号 commands。
 * 新增设备类型 = 注册表条目 + 一个本类子类 Bean。
 */
public abstract class AbstractDeviceAdapter implements DeviceAdapter {

    private final DeviceServiceClient client;
    private final DeviceTypeRegistryProperties registry;

    protected AbstractDeviceAdapter(DeviceServiceClient client, DeviceTypeRegistryProperties registry) {
        this.client = client;
        this.registry = registry;
    }

    /** 探测(免确认,FR-008):读取设备 state。 */
    @Override
    public Map<String, Object> getDiagnostics(Device device) {
        return client.getDeviceState(device.getSimulatorDeviceId());
    }

    /** 改变状态的操作(必经用户确认后才进入这里):"start" 走启动端点,其余走命令端点。 */
    @Override
    public RepairOutcome executeRepair(Device device, String actionCode, Map<String, Object> params) {
        if (!supportedRepairActions(device).contains(actionCode)) {
            return new RepairOutcome(false, "动作不在白名单: " + actionCode);
        }
        DeviceServiceClient.RepairResult result = "start".equals(actionCode)
                ? client.startDevice(device.getSimulatorDeviceId())
                : client.executeCommand(device.getSimulatorDeviceId(), actionCode, params);
        return new RepairOutcome(result.success(), result.message());
    }

    @Override
    public List<String> supportedRepairActions() {
        return List.of();
    }

    /** 按型号白名单(state 操作命令 + start)。 */
    public List<String> supportedRepairActions(Device device) {
        DeviceTypeRegistryProperties.DeviceTypeSpec spec = registry.specOf(deviceType());
        if (spec == null || spec.models() == null) {
            return List.of("start");
        }
        DeviceTypeRegistryProperties.ModelSpec model =
                spec.models().get(device.getDeviceModelCode());
        List<String> commands = model == null || model.commands() == null
                ? List.of() : model.commands();
        return new java.util.ArrayList<>(commands) {{
            add("start");
        }};
    }

    protected DeviceTypeRegistryProperties.ModelSpec modelSpecOf(Device device) {
        DeviceTypeRegistryProperties.DeviceTypeSpec spec = registry.specOf(deviceType());
        return spec == null || spec.models() == null
                ? null : spec.models().get(device.getDeviceModelCode());
    }
}
