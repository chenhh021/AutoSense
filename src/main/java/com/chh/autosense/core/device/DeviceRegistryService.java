package com.chh.autosense.core.device;

import com.chh.autosense.exception.ApiException;
import com.chh.autosense.exception.ErrorCode;
import com.chh.autosense.core.device.client.DeviceServiceClient;
import com.chh.autosense.core.device.client.DeviceServiceClient.DeviceLookupRequestException;
import com.chh.autosense.core.device.client.DeviceServiceClient.DeviceLookupResult;
import com.chh.autosense.core.device.client.DeviceServiceClient.DeviceServiceUnavailableException;
import com.chh.autosense.domain.entity.Device;
import com.chh.autosense.mapper.DeviceMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 设备绑定与查询(FR-020/R24~R26)。远程只读查询不处于数据库事务中；最终由
 * uk_device_sn 作为并发绑定的唯一裁决。
 */
@Service
public class DeviceRegistryService {

    private static final Pattern SN_PATTERN = Pattern.compile("^[A-Z0-9]{4}[0-9]{9}$");
    private static final String ALREADY_BOUND_MESSAGE = "该 SN 已绑定";
    private static final String NOT_FOUND_MESSAGE = "设备不存在或不在线";
    private static final String UNAVAILABLE_MESSAGE = "设备服务暂不可用，请稍后重试";

    private final DeviceServiceClient deviceServiceClient;
    private final DeviceMapper deviceMapper;

    public DeviceRegistryService(DeviceServiceClient deviceServiceClient,
                                 DeviceMapper deviceMapper) {
        this.deviceServiceClient = deviceServiceClient;
        this.deviceMapper = deviceMapper;
    }

    public Device register(long userId, String sn, String name) {
        validate(sn, name);

        if (deviceMapper.selectOneBySn(sn) != null) {
            throw new ApiException(ErrorCode.DEVICE_ALREADY_BOUND, ALREADY_BOUND_MESSAGE);
        }

        DeviceLookupResult lookup;
        try {
            lookup = deviceServiceClient.findDeviceBySn(sn);
        } catch (DeviceLookupRequestException e) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "SN 格式不合法");
        } catch (DeviceServiceUnavailableException e) {
            throw new ApiException(ErrorCode.DEVICE_SERVICE_UNAVAILABLE, UNAVAILABLE_MESSAGE);
        }

        if (lookup == null || lookup.exists() == null) {
            throw new ApiException(ErrorCode.DEVICE_SERVICE_UNAVAILABLE, UNAVAILABLE_MESSAGE);
        }
        if (!lookup.exists()) {
            throw new ApiException(ErrorCode.DEVICE_NOT_FOUND, NOT_FOUND_MESSAGE);
        }
        if (!isCompleteAndConsistent(sn, lookup)) {
            throw new ApiException(ErrorCode.DEVICE_SERVICE_UNAVAILABLE, UNAVAILABLE_MESSAGE);
        }

        Device device = new Device();
        device.setUserId(userId);
        device.setSn(lookup.sn());
        device.setName(name);
        device.setSimulatorName(lookup.name());
        device.setSimulatorDeviceId(lookup.simulatorDeviceId());
        device.setDeviceTypeCode(lookup.deviceTypeCode());
        device.setDeviceTypeId(lookup.deviceTypeId());
        device.setDeviceModelCode(lookup.deviceModelCode());
        device.setDeviceModelId(lookup.deviceModelId());
        try {
            deviceMapper.insert(device);
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.DEVICE_ALREADY_BOUND, ALREADY_BOUND_MESSAGE);
        }
        return device;
    }

    public List<Device> listMine(long userId) {
        return deviceMapper.selectMine(userId);
    }

    /**
     * 实时探测设备是否在线：以模拟器按 SN 查询的 exists 为准(true 在线/false 离线)。
     * 探测异常(模拟器不可达、响应无效等)一律按离线处理，不影响列表整体返回。
     */
    public boolean isOnline(Device device) {
        try {
            DeviceLookupResult lookup = deviceServiceClient.findDeviceBySn(device.getSn());
            return lookup != null && Boolean.TRUE.equals(lookup.exists());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void validate(String sn, String name) {
        if (sn == null || !SN_PATTERN.matcher(sn).matches()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "SN 格式不合法");
        }
        if (name == null || name.isBlank()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "设备名称不能为空");
        }
        if (name.length() > 64) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "设备名称不能超过 64 个字符");
        }
    }

    private boolean isCompleteAndConsistent(String requestedSn, DeviceLookupResult lookup) {
        return requestedSn.equals(lookup.sn())
                && lookup.simulatorDeviceId() != null && lookup.simulatorDeviceId() > 0
                && lookup.name() != null && !lookup.name().isBlank()
                && lookup.name().length() <= 128
                && lookup.deviceTypeCode() != null && !lookup.deviceTypeCode().isBlank()
                && lookup.deviceTypeCode().length() <= 32
                && lookup.deviceTypeId() != null && lookup.deviceTypeId() > 0
                && lookup.deviceModelCode() != null && !lookup.deviceModelCode().isBlank()
                && lookup.deviceModelCode().length() <= 32
                && lookup.deviceModelId() != null && lookup.deviceModelId() > 0;
    }
}
