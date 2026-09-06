package com.chh.autosense.domain.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 修复知识(data-model.md §6,统一术语"修复知识/Repair Knowledge")。
 */
@Data
@Table("repair_knowledge")
public class RepairKnowledge {
    @Id(keyType = KeyType.Auto)
    private Long id;
    private String deviceType;
    private String problemPattern;
    private String solutionContent;
    private Boolean autoExecutable;
    private String repairActionCode;
    private Boolean disruptive;
    private String manualSteps;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
