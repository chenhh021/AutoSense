package com.chh.autosense.api.dto;

/**
 * 设备绑定视图(contracts §6/§7)。supported 由当前注册表动态派生，不落库；
 * online 为列表查询时按 SN 实时探测的运行状态(exists=true 在线)，不落库。
 */
public record DeviceView(
        Long id,
        String name,
        String simulatorName,
        String sn,
        String deviceTypeCode,
        Long deviceTypeId,
        String deviceModelCode,
        Long deviceModelId,
        boolean supported,
        boolean online
) {
}
