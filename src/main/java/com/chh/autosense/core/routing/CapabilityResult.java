package com.chh.autosense.core.routing;

import com.chh.autosense.domain.dto.ConclusionDto;
import com.chh.autosense.domain.enums.SessionStatus;
import com.chh.autosense.exception.ErrorCode;

public record CapabilityResult(Kind kind, String text, SessionStatus status, ConclusionDto conclusion, ErrorCode error) {
    public enum Kind { COMPLETE, WAIT, FAIL, REROUTE }

    public static CapabilityResult answer(String text) {
        return new CapabilityResult(Kind.COMPLETE, text, SessionStatus.COMPLETED_ANSWERED,
                new ConclusionDto("ANSWERED", text, null, null, null, null), null);
    }
    public static CapabilityResult waiting(String text, SessionStatus status) {
        if (!status.isAwaitingUser()) throw new IllegalArgumentException("Waiting state required");
        return new CapabilityResult(Kind.WAIT, text, status, null, null);
    }
    public static CapabilityResult failure(ErrorCode error, String text) {
        return new CapabilityResult(Kind.FAIL, text, SessionStatus.FAILED_REQUEST, null, error);
    }
    public static CapabilityResult reroute(String cancellationText) {
        return new CapabilityResult(Kind.REROUTE, cancellationText, SessionStatus.COMPLETED_UNFIXED, null, null);
    }
}
