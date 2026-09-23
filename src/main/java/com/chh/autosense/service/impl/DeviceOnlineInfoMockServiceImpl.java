package com.chh.autosense.service.impl;

import com.chh.autosense.ai.model.*;
import com.chh.autosense.config.DeviceQueryProperties;
import com.chh.autosense.domain.dto.DeviceCapabilityDefinition;
import com.chh.autosense.domain.dto.DeviceCapabilityDefinition.Node;
import com.chh.autosense.exception.*;
import com.chh.autosense.service.DeviceOnlineInfoService;
import com.fasterxml.jackson.databind.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.function.LongFunction;
import java.util.random.RandomGenerator;

@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "autosense.device-query", name = "runtime-provider", havingValue = "mock", matchIfMissing = true)
@Slf4j
public class DeviceOnlineInfoMockServiceImpl implements DeviceOnlineInfoService {
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    private final DeviceQueryProperties properties;
    private final LongFunction<RandomGenerator> random;
    @Autowired public DeviceOnlineInfoMockServiceImpl(DeviceQueryProperties properties) { this(properties, SplittableRandom::new); }
    public DeviceOnlineInfoMockServiceImpl(DeviceQueryProperties properties, LongFunction<RandomGenerator> random) {
        this.properties = properties; this.random = random;
    }
    @Override public RuntimeIdentity runtimeIdentity() { return new RuntimeIdentity("mock", "MOCK", ""); }
    @Override public DeviceOnlineInfo read(DeviceCapabilityDefinition definition, Invocation invocation) {
        if (!invocation.online()) throw new ApiException(ErrorCode.DEVICE_OFFLINE, "Device is offline");
        long seed = Long.parseUnsignedLong(com.chh.autosense.graph.node.PlanValidator.digest(
                List.of(invocation.workflowId(), invocation.stepId(), definition.contentHash())).substring(0, 16), 16);
        var result = values(definition, random.apply(seed));
        log.info("Device runtime attributes generated: source=MOCK, capabilityHash={}, getCount={}", definition.contentHash(), result.size());
        return new DeviceOnlineInfo("MOCK", invocation.generatedAt(), definition.contentHash(), result);
    }
    @Override public void validateModel(DeviceCapabilityDefinition definition) { DeviceAttributeModels.validateModel(definition); }

    private Map<String, DeviceAttributeModels.Attributes> values(DeviceCapabilityDefinition definition, RandomGenerator random) {
        try {
            var values = new LinkedHashMap<String, DeviceAttributeModels.Attributes>();
            for (var capability : definition.capabilities().values()) {
                if (!capability.type().equals("GET")) continue;
                var generated = JSON.valueToTree(generate(capability.result(), "", random));
                var result = DeviceAttributeModels.decode(definition, capability.code(), generated);
                values.put(capability.code(), result);
            }
            return values;
        } catch (Exception e) { throw new ApiException(ErrorCode.DEVICE_ONLINE_INFO_INVALID, "Model attributes do not match capability definition"); }
    }

    private Object generate(Node node, String name, RandomGenerator random) {
        if (name.equals("online") && node.dataType().equals("BOOLEAN")) return true;
        if (!node.enumValues().isEmpty()) return node.enumValues().get(random.nextInt(node.enumValues().size()));
        return switch (node.dataType()) {
            case "OBJECT" -> {
                var value = new LinkedHashMap<String, Object>();
                node.properties().forEach((key, child) -> value.put(key, generate(child, key, random))); yield value;
            }
            case "BOOLEAN" -> name.equals("online") || random.nextBoolean();
            case "STRING" -> "MOCK-" + name;
            case "INTEGER", "NUMBER" -> {
                var defaults = properties.mockDefaultRanges().getOrDefault(name,
                        properties.mockDefaultRanges().get(node.dataType().toLowerCase(Locale.ROOT)));
                double min = node.min() == null ? Math.min(defaults.min(), node.max() == null ? defaults.min() : node.max()) : node.min();
                double max = node.max() == null ? Math.max(defaults.max(), min) : node.max();
                if (node.dataType().equals("INTEGER")) {
                    long low = (long) Math.ceil(min), high = (long) Math.floor(max);
                    if (low > high || low < Integer.MIN_VALUE || high > Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid integer range");
                    yield (int) (low == high ? low : random.nextLong(low, high + 1));
                }
                double weight = random.nextDouble();
                yield min == max ? min : min * (1 - weight) + max * weight;
            }
            default -> throw new IllegalArgumentException("Unsupported property type");
        };
    }
}
