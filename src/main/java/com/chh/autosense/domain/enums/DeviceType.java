package com.chh.autosense.domain.enums;

import java.util.Arrays;

/**
 * 设备类型;受支持范围由 yaml 注册表(autosense.device-types)决定,本枚举仅作
 * 已知类型的规范化载体。新增设备类型无需修改核心流程(FR-012)。
 */
public enum DeviceType {
    ROUTER("router"),
    AIR_CONDITIONER("air_conditioner"),
    SMART_BULB("smart_bulb");

    private final String code;

    DeviceType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static DeviceType fromCode(String code) {
        return Arrays.stream(values())
                .filter(t -> t.code.equalsIgnoreCase(code))
                .findFirst()
                .orElse(null);
    }
}
