package com.chh.autosense.domain.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;

public record WorkflowCancelRequest(@NotNull @PositiveOrZero Long expectedVersion) {
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown cancel field"); }
}
