package com.chh.autosense.device.client;

import com.chh.autosense.device.spi.DeviceUnreachableException;

import java.util.Map;

/**
 * 设备服务客户端(research R11,2026-08-27 修订):唯一实现为 HTTP 调用外部
 * deviceSimulator(契约见 documents/新增接口说明-按SN查询设备.md);base-url 经配置注入。
 * 读取类方法为探测(免确认,FR-008);命令/启动为改变设备状态的操作(必经用户确认)。
 */
public interface DeviceServiceClient {

    /**
     * 读取设备当前 state(探测;GET /api/v1/devices/{id}/data)。
     *
     * @param simulatorDeviceId 模拟器侧设备 id
     * @return 设备 state 键值(结构与型号相关)
     * @throws DeviceUnreachableException 设备不存在或已停止(模拟器 404,FR-014)
     */
    Map<String, Object> getDeviceState(long simulatorDeviceId);

    /**
     * 下发设备命令(POST /api/v1/devices/{id}/commands)。
     *
     * @return 执行结果;success=false 表示失败(失败即停,FR-017,message 透传模拟器错误)
     * @throws DeviceUnreachableException 设备不存在或已停止
     */
    RepairResult executeCommand(long simulatorDeviceId, String command, Map<String, Object> parameters);

    /**
     * 启动已停止设备(POST /api/v1/devices/{id}/start)。
     *
     * @throws DeviceUnreachableException 设备不存在
     */
    RepairResult startDevice(long simulatorDeviceId);

    /**
     * 按 SN 发现已存在且运行中的模拟器设备(FR-020)。
     */
    DeviceLookupResult findDeviceBySn(String sn);

    record RepairResult(boolean success, String message) {
    }

    /**
     * 查询响应的稳定字段。exists 使用 Boolean 是为了区分 false 与协议缺少字段。
     */
    record DeviceLookupResult(
            Boolean exists,
            Long simulatorDeviceId,
            String sn,
            String name,
            String deviceTypeCode,
            Long deviceTypeId,
            String deviceModelCode,
            Long deviceModelId
    ) {
    }

    /** 上游认为 SN 请求无效；映射为本平台 400。 */
    class DeviceLookupRequestException extends RuntimeException {
        public DeviceLookupRequestException(String message) {
            super(message);
        }
    }

    /** 上游不可用或返回不符合契约；映射为本平台 503。 */
    class DeviceServiceUnavailableException extends RuntimeException {
        public DeviceServiceUnavailableException(String message) {
            super(message);
        }

        public DeviceServiceUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
