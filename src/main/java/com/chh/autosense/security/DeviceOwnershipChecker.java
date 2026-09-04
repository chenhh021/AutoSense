package com.chh.autosense.security;

import com.chh.autosense.api.ApiException;
import com.chh.autosense.api.ErrorCode;
import com.chh.autosense.domain.model.Device;
import org.springframework.stereotype.Component;

/**
 * 设备归属校验(FR-015):设备必须属于当前登录用户。
 */
@Component
public class DeviceOwnershipChecker {

    public void check(Device device, AuthUser user) {
        if (device == null) {
            throw new ApiException(ErrorCode.DEVICE_NOT_FOUND, "设备不存在");
        }
        if (!device.getUserId().equals(user.userId())) {
            throw new ApiException(ErrorCode.DEVICE_FORBIDDEN, "无权操作该设备");
        }
    }
}
