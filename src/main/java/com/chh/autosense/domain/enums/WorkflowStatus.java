package com.chh.autosense.domain.enums;

public enum WorkflowStatus {
    CREATED, PLANNING, VALIDATING, RUNNING, WAITING_APPROVAL, WAITING_INPUT,
    RETRYING, WAITING_RESUME, COMPLETED, FAILED, REJECTED, CANCELLED;

    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == REJECTED || this == CANCELLED;
    }
}
