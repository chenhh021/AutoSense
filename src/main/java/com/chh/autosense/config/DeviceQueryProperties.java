package com.chh.autosense.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Device query budgets are server configuration, never LLM parameters. */
@ConfigurationProperties("autosense.device-query")
public record DeviceQueryProperties(
        @DefaultValue("mock") String runtimeProvider,
        @DefaultValue("SIMULATOR") String externalSource,
        @DefaultValue("10s") Duration initializationTimeout,
        @DefaultValue("4") int onlineLookupParallelism,
        @DefaultValue("50") int maxSns,
        @DefaultValue("262144") int maxPlannerDeviceBytes,
        @DefaultValue("3600s") Duration capabilityCacheTtl,
        Map<String, NumericRange> mockDefaultRanges) {

    public DeviceQueryProperties(String runtimeProvider, Duration initializationTimeout, int onlineLookupParallelism,
                                 int maxSns, int maxPlannerDeviceBytes, Duration capabilityCacheTtl, Map<String, NumericRange> ranges) {
        this(runtimeProvider, "SIMULATOR", initializationTimeout, onlineLookupParallelism, maxSns, maxPlannerDeviceBytes, capabilityCacheTtl, ranges);
    }

    public record NumericRange(double min, double max) {
        public NumericRange {
            if (!Double.isFinite(min) || !Double.isFinite(max) || min > max)
                throw new IllegalArgumentException("Invalid mock numeric range");
        }
    }

    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public DeviceQueryProperties {
        if (!java.util.Set.of("mock", "real").contains(runtimeProvider)) throw new IllegalArgumentException("Unsupported runtime provider");
        if (!java.util.Set.of("SIMULATOR", "REAL").contains(externalSource)) throw new IllegalArgumentException("Unsupported external source");
        if (initializationTimeout == null || initializationTimeout.isNegative() || initializationTimeout.isZero()
                || capabilityCacheTtl == null || capabilityCacheTtl.isNegative() || capabilityCacheTtl.isZero()
                || onlineLookupParallelism < 1 || onlineLookupParallelism > 64 || maxSns < 1 || maxSns > 1000
                || maxPlannerDeviceBytes < 1 || maxPlannerDeviceBytes > 4 * 1024 * 1024)
            throw new IllegalArgumentException("Invalid device query budget");
        var ranges = new LinkedHashMap<>(Map.of("integer", new NumericRange(0, 100),
                "number", new NumericRange(0, 100), "signal_strength", new NumericRange(-100, -30),
                "current_temperature", new NumericRange(16, 35)));
        if (mockDefaultRanges != null) ranges.putAll(mockDefaultRanges);
        mockDefaultRanges = Map.copyOf(ranges);
    }
}
