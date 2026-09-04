package com.chh.autosense.domain.model;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 诊断快照(data-model.md §4);payload 为 JSON 字符串,键集由设备类型注册表定义。
 */
@Data
@Table("diagnostic_snapshot")
public class DiagnosticSnapshot {
    @Id(keyType = KeyType.Auto)
    private Long id;
    private Long sessionId;
    /** 所属轮次(R17 终态续聊) */
    private Integer round;
    private String phase;
    private String payload;
    private LocalDateTime createdAt;
}
