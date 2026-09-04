package com.chh.autosense.analysis;

/**
 * 语义分析结果(FR-002):从用户输入提取的关键属性。
 *
 * @param sufficient 设备类型与问题表现是否足以继续;false 时 clarifyQuestion 必填
 */
public record ProblemAnalysis(
        String deviceType,
        String symptom,
        String reproduction,
        boolean sufficient,
        String clarifyQuestion
) {
}
