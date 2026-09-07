package com.chh.autosense.ai.model;

import com.chh.autosense.ai.model.enums.CapabilityIntent;
import com.chh.autosense.ai.model.enums.DiagnosisMode;
import com.chh.autosense.ai.model.enums.RoutingOutcome;

/** Untrusted classification data; contains no authorization or verified device identity. */
public record RoutingDecision(RoutingOutcome outcome, CapabilityIntent intent, DiagnosisMode diagnosisMode,
                              String targetHint, String clarifyQuestion) { }
