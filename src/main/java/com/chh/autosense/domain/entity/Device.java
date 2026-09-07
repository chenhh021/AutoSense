package com.chh.autosense.domain.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 设备绑定(data-model.md §1)。只持久化稳定模拟器元数据；运行状态按需实时读取。
 */
@Getter
@Setter
@NoArgsConstructor
@Table("device")
public class Device {
    @Id(keyType = KeyType.Auto)
    private Long id;
    private Long userId;
    /** 大小写敏感的 13 位模拟器序列号。 */
    private String sn;
    /** 用户提交的平台显示名称。 */
    private String name;
    /** deviceSimulator 返回的原始设备名称。 */
    private String simulatorName;
    private Long simulatorDeviceId;
    private String deviceTypeCode;
    private Long deviceTypeId;
    private String deviceModelCode;
    private Long deviceModelId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
