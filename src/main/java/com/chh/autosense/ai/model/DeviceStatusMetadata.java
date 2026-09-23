package com.chh.autosense.ai.model;
import java.time.Instant;
public record DeviceStatusMetadata(String source, Instant observedAt, String errorCode, String reason) { }
