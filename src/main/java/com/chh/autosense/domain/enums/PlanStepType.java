package com.chh.autosense.domain.enums;

public enum PlanStepType {
    KNOWLEDGE_CONSULT, DEVICE_QUERY, FAULT_DIAGNOSIS, DEVICE_CONTROL;

    public boolean requiresApproval() {
        return this == DEVICE_QUERY || this == DEVICE_CONTROL;
    }
}
