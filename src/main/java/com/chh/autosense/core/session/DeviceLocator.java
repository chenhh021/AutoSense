package com.chh.autosense.core.session;

import com.chh.autosense.service.device.DeviceAdapterRegistryService;
import com.chh.autosense.domain.entity.Device;
import com.chh.autosense.mapper.DeviceMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 设备定位(FR-003,T022):在用户名下设备中按类型定位;
 * 多候选返回列表由用户确认,零匹配由调用方按 DEVICE_NOT_FOUND 处理。
 */
@Component
public class DeviceLocator {

    private final DeviceMapper deviceMapper;
    private final DeviceAdapterRegistryService adapterRegistry;

    public DeviceLocator(DeviceMapper deviceMapper, DeviceAdapterRegistryService adapterRegistry) {
        this.deviceMapper = deviceMapper;
        this.adapterRegistry = adapterRegistry;
    }

    public List<Device> findCandidates(Long userId, String deviceType) {
        return findCandidates(userId, deviceType, null);
    }

    /** 按类型(可选型号)在用户绑定设备中定位(FR-003,型号感知)。 */
    public List<Device> findCandidates(Long userId, String deviceType, String deviceModel) {
        return deviceMapper.selectMine(userId).stream()
                .filter(device -> deviceType == null
                        || adapterRegistry
                        .diagnosticTypeForSimulatorType(device.getDeviceTypeCode())
                        .filter(deviceType::equals)
                        .isPresent())
                .filter(device -> deviceModel == null || deviceModel.isBlank()
                        || deviceModel.equals(device.getDeviceModelCode()))
                .toList();
    }

    public List<Device> findByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return deviceMapper.selectListByQuery(QueryWrapper.create()
                .where("id IN (" + String.join(",",
                        ids.stream().map(String::valueOf).toList()) + ")"));
    }

    /**
     * 从候选中匹配用户确认输入(序号 1..N 或设备名称包含匹配)。
     */
    public Device pickFromCandidates(List<Device> candidates, String userInput) {
        if (userInput == null) {
            return null;
        }
        String input = userInput.trim();
        try {
            int index = Integer.parseInt(input);
            if (index >= 1 && index <= candidates.size()) {
                return candidates.get(index - 1);
            }
        } catch (NumberFormatException ignored) {
        }
        return candidates.stream()
                .filter(d -> d.getName().contains(input) || input.contains(d.getName()))
                .findFirst()
                .orElse(null);
    }
}
