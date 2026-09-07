package com.chh.autosense.ai.model;

/**
 * 诊断结论(FR-006):LLM 结合诊断快照与用户问题推断的结果。
 */
public record DiagnosisConclusion(
        /** 问题摘要(用于 RAG 检索) */
        String problemSummary,
        /** 面向用户的可理解结论 */
        String conclusionText,
        /** 是否疑似可自动修复 */
        boolean likelyAutoFixable
) {
}
