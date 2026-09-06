package com.chh.autosense.domain.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 修复会话(data-model.md §3);终态与迁移见 SessionStatus 状态机。
 */
@Data
@Table("repair_session")
public class RepairSession {
    @Id(keyType = KeyType.Auto)
    private Long id;
    private Long userId;
    private Long deviceId;
    private String status;
    private String conclusionType;
    private String conclusion;
    /** 结论附加数据(JSON):manualSteps / afterSales 等 */
    private String conclusionExtra;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
