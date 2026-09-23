package com.chh.autosense.ai.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.*;
import com.chh.autosense.domain.dto.DeviceCapabilityDefinition;
import com.chh.autosense.domain.dto.DeviceCapabilityDefinition.Node;
import com.chh.autosense.domain.enums.DeviceType;
import com.chh.autosense.exception.ApiException;
import com.chh.autosense.exception.ErrorCode;
import com.fasterxml.jackson.databind.*;

/** Explicit model contracts. JSON contains values only; no polymorphic type metadata. */
public final class DeviceAttributeModels {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    private DeviceAttributeModels() { }
    public sealed interface Attributes permits Mjdpl01yl, Mjdp09yl, Kfr035gw, Blj48wd240200pv { }
    public record Rgb(Integer red, Integer green, Integer blue) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Mjdpl01yl(Boolean power, Integer brightness, Rgb color, Boolean online,
                           Integer signalStrength, String firmwareVersion) implements Attributes { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Mjdp09yl(Boolean power, Integer brightness, Integer colorTemperature, Boolean online,
                          Integer signalStrength, String firmwareVersion) implements Attributes { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Kfr035gw(Boolean power, String mode, Double currentTemperature, Double targetTemperature,
                          String fanSpeed, String swing, Boolean ecoMode, Boolean sleepMode, String compressorStatus,
                          String errorCode, Integer filterLife, Boolean online, String firmwareVersion) implements Attributes { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Blj48wd240200pv(Boolean power, String mode, String fanSpeed, Double pm25, String airQuality,
                                Double temperature, Double humidity, Integer filterLife, String motorStatus,
                                String errorCode, Boolean childLock, Integer displayBrightness, Boolean online,
                                String firmwareVersion) implements Attributes { }

    private static final Map<String, Class<? extends Attributes>> MODELS = Map.of(
            "SMART_BULB/MJDPL01YL/get_properties", Mjdpl01yl.class,
            "SMART_BULB/MJDP09YL/get_properties", Mjdp09yl.class,
            "AIR_CONDITIONER/KFR035GW/get_properties", Kfr035gw.class,
            "AIR_PURIFIER/BLJ48WD240200PV/get_properties", Blj48wd240200pv.class);
    public static Class<? extends Attributes> type(String deviceType, String model, String getCode) {
        var known = DeviceType.fromCode(deviceType);
        var result = known == null ? null : MODELS.get(known.name() + "/" + model + "/" + getCode);
        if (result == null) throw new IllegalArgumentException("No structured attribute model registered");
        return result;
    }

    /** Safe diagnostics contain only local schema paths and fixed reason codes. */
    public static final class InvalidAttributes extends ApiException {
        private final Map<String, String> diagnostics;
        public InvalidAttributes(Map<String, String> diagnostics) {
            super(ErrorCode.DEVICE_ONLINE_INFO_INVALID, "Device attributes do not match the model contract");
            this.diagnostics = Map.copyOf(diagnostics);
        }
        public Map<String, String> diagnostics() { return diagnostics; }
    }

    public static void validateModel(DeviceCapabilityDefinition definition) {
        try {
            for (var capability : definition.capabilities().values()) {
                if ("GET".equals(capability.type())) validateType(capability.result(),
                        JSON.constructType(type(definition.deviceType(), definition.deviceModel(), capability.code())));
            }
        } catch (RuntimeException e) { throw new InvalidAttributes(Map.of()); }
    }

    private static void validateType(Node node, JavaType type) {
        Class<?> raw = type.getRawClass();
        boolean matches = switch (node.dataType()) {
            case "BOOLEAN" -> raw == Boolean.class;
            case "STRING" -> raw == String.class;
            case "INTEGER" -> raw == Integer.class;
            case "NUMBER" -> raw == Double.class;
            case "OBJECT" -> raw.isRecord();
            default -> false;
        };
        if (!matches) throw new IllegalArgumentException("Model type mismatch");
        if ("OBJECT".equals(node.dataType())) {
            var fields = JSON.getDeserializationConfig().introspect(type).findProperties();
            if (!node.properties().keySet().equals(fields.stream().map(p -> p.getName()).collect(java.util.stream.Collectors.toSet())))
                throw new IllegalArgumentException("Model fields mismatch");
            fields.forEach(field -> validateType(node.properties().get(field.getName()), field.getPrimaryType()));
        }
    }

    public static Attributes decode(DeviceCapabilityDefinition definition, String getCode, JsonNode values) {
        validateModel(definition);
        var capability = definition.capabilities().get(getCode);
        if (capability == null || !"GET".equals(capability.type())) throw new InvalidAttributes(Map.of());
        var diagnostics = new LinkedHashMap<String, String>();
        diagnose(capability.result(), values, "onlineInfo.getResults." + getCode, diagnostics);
        if (!diagnostics.isEmpty()) throw new InvalidAttributes(diagnostics);
        try {
            capability.result().validate(values);
            var result = JSON.treeToValue(values, type(definition.deviceType(), definition.deviceModel(), getCode));
            var roundTrip = JSON.valueToTree(result);
            capability.result().validate(roundTrip);
            if (!equivalent(capability.result(), values, roundTrip)) throw new IllegalArgumentException("Lossy conversion");
            return result;
        } catch (Exception e) { throw new InvalidAttributes(Map.of()); }
    }

    private static boolean equivalent(Node node, JsonNode before, JsonNode after) {
        if ("NUMBER".equals(node.dataType())) return before.decimalValue().compareTo(after.decimalValue()) == 0;
        if ("OBJECT".equals(node.dataType())) return node.properties().entrySet().stream()
                .allMatch(entry -> equivalent(entry.getValue(), before.get(entry.getKey()), after.get(entry.getKey())));
        return before.equals(after);
    }

    private static void diagnose(Node node, JsonNode value, String path, Map<String, String> errors) {
        if (value == null) { errors.put(path, "MISSING_FIELD"); return; }
        boolean valid = switch (node.dataType()) {
            case "BOOLEAN" -> value.isBoolean();
            case "STRING" -> value.isTextual();
            case "INTEGER" -> value.isIntegralNumber() && value.canConvertToInt();
            case "NUMBER" -> value.isNumber() && Double.isFinite(value.doubleValue());
            case "OBJECT" -> value.isObject();
            default -> false;
        };
        if (!valid) { errors.put(path, "INVALID_TYPE"); return; }
        if (value.isObject()) {
            value.fieldNames().forEachRemaining(key -> {
                if (!node.properties().containsKey(key)) errors.put("onlineInfoSchema", "UNEXPECTED_FIELD");
            });
            node.properties().forEach((key, child) -> diagnose(child, value.get(key), path + "." + key, errors));
            return;
        }
        if (!node.enumValues().isEmpty() && node.enumValues().stream().noneMatch(item -> {
            JsonNode expected = JSON.valueToTree(item);
            return value.isNumber() && expected.isNumber()
                    ? value.decimalValue().compareTo(expected.decimalValue()) == 0 : value.equals(expected);
        })) { errors.put(path, "INVALID_ENUM"); return; }
        if (value.isNumber() && (node.min() != null && value.decimalValue().compareTo(java.math.BigDecimal.valueOf(node.min())) < 0
                || node.max() != null && value.decimalValue().compareTo(java.math.BigDecimal.valueOf(node.max())) > 0))
            errors.put(path, "OUT_OF_RANGE");
    }
}
