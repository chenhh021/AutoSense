package com.chh.autosense.domain.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;

public record WorkflowApprovalRequest(@NotBlank @Size(max = 64) String stepId,
        @NotBlank @Size(max = 36) String approvalId, @NotNull Boolean approved,
        @NotNull @PositiveOrZero Long expectedVersion) {
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown approval field"); }
}
