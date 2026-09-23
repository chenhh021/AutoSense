package com.chh.autosense.domain.enums;

import java.util.Arrays;

/**
 * 设备类型;受支持范围由 yaml 注册表(autosense.device-types)决定,本枚举仅作
 * 已知类型的规范化载体。新增设备类型无需修改核心流程(FR-012)。
 */
public enum DeviceType {
    ROUTER("router", "ROUTER"),
    AIR_CONDITIONER("air_conditioner", "AIRC"),
    SMART_BULB("smart_bulb", "LIGHT"),
    AIR_PURIFIER("air_purifier", "PURI");

    private final String code;
    private final String externalCode;

    DeviceType(String code, String externalCode) {
        this.code = code;
        this.externalCode = externalCode;
    }

    public String code() {
        return code;
    }

    public String externalCode() {
        return externalCode;
    }

    /** 仅在能力和适配器查找时规范化；持久化及设备快照保留外部原始类型码。 */
    public static DeviceType fromCode(String code) {
        return Arrays.stream(values())
                .filter(t -> t.code.equalsIgnoreCase(code) || t.externalCode.equalsIgnoreCase(code))
                .findFirst()
                .orElse(null);
    }
}
