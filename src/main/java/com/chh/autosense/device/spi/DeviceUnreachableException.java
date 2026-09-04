package com.chh.autosense.device.spi;

/**
 * 设备不可达(离线/超时,FR-014):状态机捕获后转 FAILED_DEVICE_UNREACHABLE。
 */
public class DeviceUnreachableException extends RuntimeException {

    public DeviceUnreachableException(String message) {
        super(message);
    }

    public DeviceUnreachableException(String message, Throwable cause) {
        super(message, cause);
    }
}
