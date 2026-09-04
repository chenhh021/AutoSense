package com.chh.autosense.domain.model;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 问题报告(data-model.md §2);clarifications 为 JSON 数组字符串。
 */
@Data
@Table("problem_report")
public class ProblemReport {
    @Id(keyType = KeyType.Auto)
    private Long id;
    private Long sessionId;
    /** 会话内轮次号(终态续聊每轮新建一条,R17) */
    private Integer round;
    /** 路由意图(FR-019):COMMON_SENSE / DEVICE_ACTION / AFTERSALES_QUERY */
    private String intent;
    private String rawText;
    private String deviceType;
    private String symptom;
    private String reproduction;
    private String clarifications;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
