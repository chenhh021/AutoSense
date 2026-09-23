package com.chh.autosense.domain.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Validated, immutable DSL, independent of tool wire objects and Redis. */
public record DeviceCapabilityDefinition(String deviceType, String deviceModel, String rawJson, String contentHash,
                                         Map<String, Capability> capabilities) {
    public DeviceCapabilityDefinition { capabilities = Collections.unmodifiableMap(new LinkedHashMap<>(capabilities)); }
    public record Capability(String code, String type, Node result, Node parameters, Map<String, Node> arguments) {
        public Capability { arguments = Collections.unmodifiableMap(new LinkedHashMap<>(arguments)); }
    }
    public record Node(String dataType, Map<String, Node> properties, List<Object> enumValues,
                       Double min, Double max, String unit, boolean required) {
        public Node { properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties)); enumValues = List.copyOf(enumValues); }
        public void validate(JsonNode value) {
            boolean valid = switch (dataType) {
                case "BOOLEAN" -> value != null && value.isBoolean();
                case "STRING" -> value != null && value.isTextual();
                case "INTEGER" -> value != null && value.isIntegralNumber() && value.canConvertToInt();
                case "NUMBER" -> value != null && value.isNumber() && Double.isFinite(value.doubleValue());
                case "OBJECT" -> value != null && value.isObject() && value.size() == properties.size();
                default -> false;
            };
            if (!valid) throw new IllegalArgumentException("Device property type or shape mismatch");
            if (dataType.equals("OBJECT")) properties.forEach((name, child) -> child.validate(value.get(name)));
            if (value.isNumber() && (min != null && value.doubleValue() < min || max != null && value.doubleValue() > max))
                throw new IllegalArgumentException("Device property outside bounds");
            if (!enumValues.isEmpty()) {
                Object scalar = value.isTextual() ? value.textValue() : value.isBoolean() ? value.booleanValue() : value.numberValue();
                if (enumValues.stream().noneMatch(item -> item instanceof Number n && scalar instanceof Number number
                        ? Double.compare(n.doubleValue(), number.doubleValue()) == 0 : item.equals(scalar)))
                    throw new IllegalArgumentException("Device property outside enum");
            }
        }
    }
    public List<String> getScope() {
        var paths = new ArrayList<String>();
        capabilities.values().stream().filter(c -> c.type().equals("GET"))
                .forEach(c -> collect("getResults." + c.code(), c.result(), paths));
        return List.copyOf(paths);
    }
    private static void collect(String path, Node node, List<String> output) {
        output.add(path); node.properties().forEach((key, child) -> collect(path + "." + key, child, output));
    }
    public Node field(String path) {
        if (path == null || !path.matches("getResults\\.[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*){1,8}"))
            throw new IllegalArgumentException("Invalid property path");
        String[] parts = path.split("\\."); var capability = capabilities.get(parts[1]);
        if (capability == null || !"GET".equals(capability.type())) throw new IllegalArgumentException("Unknown GET capability");
        Node node = capability.result();
        for (int i = 2; i < parts.length; i++) {
            node = node.properties().get(parts[i]);
            if (node == null) throw new IllegalArgumentException("Unknown model property");
        }
        return node;
    }
}
