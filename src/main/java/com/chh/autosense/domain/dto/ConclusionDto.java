package com.chh.autosense.domain.dto;

import com.chh.autosense.core.aftersales.AfterSalesLocation;

import java.util.List;
import java.util.Map;

/**
 * 会话结论(contracts §3)。type ∈ FIXED / UNFIXED_MANUAL_GUIDE /
 * UNFIXED_AFTERSALES / DEVICE_UNREACHABLE。
 */
public record ConclusionDto(
        String type,
        String summary,
        Map<String, Object> preDiagnostics,
        Map<String, Object> postDiagnostics,
        List<String> manualSteps,
        List<AfterSalesLocation> afterSales
) {
}
