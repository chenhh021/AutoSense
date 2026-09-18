package com.chh.autosense.graph.state;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

/** Defensive copying of JSON values at graph boundaries; never accepts runtime objects. */
public final class StateData {
    private StateData() { }

    public static Map<String, Object> freeze(Map<String, ?> source) {
        if (source == null || source.isEmpty()) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(Objects.requireNonNull(key), freezeValue(value)));
        return Collections.unmodifiableMap(result);
    }

    public static Object freezeValue(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof Integer || value instanceof Long || value instanceof Short
                || value instanceof Byte || value instanceof BigInteger || value instanceof BigDecimal)
            return value;
        if (value instanceof Double d && Double.isFinite(d)) return d;
        if (value instanceof Float f && Float.isFinite(f)) return f;
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> {
                if (!(k instanceof String key)) throw new IllegalArgumentException("Graph object keys must be strings");
                copy.put(key, freezeValue(v));
            });
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list)
            return Collections.unmodifiableList(list.stream().map(StateData::freezeValue).toList());
        throw new IllegalArgumentException("Unsupported graph value type");
    }
}
