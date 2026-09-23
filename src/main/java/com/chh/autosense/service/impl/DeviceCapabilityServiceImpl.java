package com.chh.autosense.service.impl;

import com.chh.autosense.config.DeviceQueryProperties;
import com.chh.autosense.domain.dto.DeviceCapabilityDefinition;
import com.chh.autosense.domain.dto.DeviceCapabilityDefinition.*;
import com.chh.autosense.domain.enums.DeviceType;
import com.chh.autosense.exception.*;
import com.chh.autosense.service.DeviceCapabilityService;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service
@Slf4j
public class DeviceCapabilityServiceImpl implements DeviceCapabilityService {
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final StringRedisTemplate redis;
    private final DeviceQueryProperties properties;
    public DeviceCapabilityServiceImpl(StringRedisTemplate redis, DeviceQueryProperties properties) {
        this.redis = redis; this.properties = properties;
    }

    @Override public DeviceCapabilityDefinition get(String type, String model) {
        String normalized = normalize(type, model);
        var resource = new ClassPathResource("device/" + normalized + "/" + model + ".json");
        if (!resource.exists()) throw error(ErrorCode.DEVICE_CAPABILITY_NOT_CONFIGURED);
        String raw;
        try (var stream = resource.getInputStream()) {
            byte[] bytes = stream.readNBytes(1_048_577);
            if (bytes.length > 1_048_576) throw error(ErrorCode.DEVICE_CAPABILITY_INVALID);
            raw = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw error(ErrorCode.DEVICE_CAPABILITY_UNAVAILABLE); }
        // Content-addressed cache: a changed resource cannot reuse an earlier approval or cached version.
        // Reading packaged bytes is cheap; business data is cached only in Redis, with an explicit TTL.
        String hash = hash(raw);
        String key = "device:capability:v1:" + normalized + ":" + model + ":" + hash;
        String cached;
        try {
            cached = redis.opsForValue().get(key);
            if (cached != null && !hash(cached).equals(hash)) throw error(ErrorCode.DEVICE_CAPABILITY_UNAVAILABLE);
            var definition = parse(normalized, model, cached == null ? raw : cached);
            if (cached == null) redis.opsForValue().set(key, raw, properties.capabilityCacheTtl());
            log.info("Device capability loaded: cacheResult={}, capabilityHash={}", cached == null ? "MISS" : "HIT", hash);
            return definition;
        } catch (ApiException e) { throw e; }
        catch (RuntimeException e) {
            log.warn("Device capability unavailable: reasonCode=CACHE_UNAVAILABLE");
            throw error(ErrorCode.DEVICE_CAPABILITY_UNAVAILABLE);
        }
    }

    public static DeviceCapabilityDefinition parse(String type, String model, String raw) {
        String normalized = normalize(type, model);
        try {
            var root = JSON.readTree(raw);
            require(root != null && root.isObject()
                    && DeviceType.fromCode(normalized) == DeviceType.fromCode(root.path("deviceType").asText()));
            require(!root.has("deviceModel") || model.equals(root.path("deviceModel").textValue()));
            var entries = root.path("capabilities"); require(entries.isArray() && !entries.isEmpty() && entries.size() <= 100);
            var capabilities = new LinkedHashMap<String, Capability>();
            for (var entry : entries) {
                String code = entry.path("code").asText(), kind = entry.path("type").asText();
                require(code.matches("[A-Za-z][A-Za-z0-9_]{0,63}") && Set.of("GET", "SET", "ACTION").contains(kind)
                        && !capabilities.containsKey(code));
                Node result = null, params = null; var arguments = new LinkedHashMap<String, Node>();
                if (kind.equals("GET")) { result = node(entry.path("result"), 0); require(result.dataType().equals("OBJECT")); }
                else if (kind.equals("SET")) { params = node(entry.path("parameters"), 0); require(params.dataType().equals("OBJECT")); }
                else {
                    var values = entry.path("parameters"); require(values.isArray() && values.size() <= 100);
                    for (var value : values) {
                        String name = value.path("name").asText(); require(name.matches("[A-Za-z][A-Za-z0-9_]{0,63}") && !arguments.containsKey(name));
                        arguments.put(name, node(value, 0));
                    }
                }
                capabilities.put(code, new Capability(code, kind, result, params, arguments));
            }
            require(capabilities.values().stream().anyMatch(c -> c.type().equals("GET")));
            return new DeviceCapabilityDefinition(normalized.toUpperCase(Locale.ROOT), model, raw, hash(raw), capabilities);
        } catch (Exception e) { throw error(ErrorCode.DEVICE_CAPABILITY_INVALID); }
    }

    private static Node node(JsonNode raw, int depth) {
        require(raw.isObject() && depth <= 8);
        String type = raw.path("dataType").asText(); require(Set.of("BOOLEAN", "INTEGER", "NUMBER", "STRING", "OBJECT").contains(type));
        var children = new LinkedHashMap<String, Node>();
        if (type.equals("OBJECT")) {
            var fields = raw.path("properties"); require(fields.isObject() && !fields.isEmpty() && fields.size() <= 100);
            fields.fields().forEachRemaining(field -> {
                require(field.getKey().matches("[A-Za-z][A-Za-z0-9_]{0,63}")); children.put(field.getKey(), node(field.getValue(), depth + 1));
            });
        } else require(!raw.has("properties"));
        Double min = bound(raw, "min"), max = bound(raw, "max");
        require((min == null && max == null) || Set.of("INTEGER", "NUMBER").contains(type));
        require(min == null || max == null || min <= max);
        if (type.equals("INTEGER")) {
            require(min == null || min >= Integer.MIN_VALUE && min <= Integer.MAX_VALUE && min == Math.rint(min));
            require(max == null || max >= Integer.MIN_VALUE && max <= Integer.MAX_VALUE && max == Math.rint(max));
        }
        var enums = new ArrayList<Object>();
        if (raw.has("enumValues")) {
            var values = raw.get("enumValues"); require(values.isArray() && !values.isEmpty() && !type.equals("OBJECT"));
            var unrestricted = new Node(type, Map.of(), List.of(), min, max, "", false);
            for (var value : values) {
                unrestricted.validate(value); Object scalar = JSON.convertValue(value, Object.class);
                require(!enums.contains(scalar)); enums.add(scalar);
            }
        }
        require(!raw.has("required") || raw.get("required").isBoolean());
        require(!raw.has("unit") || raw.get("unit").isTextual());
        return new Node(type, children, enums, min, max, raw.path("unit").asText(""), raw.path("required").asBoolean(false));
    }
    private static Double bound(JsonNode value, String key) {
        if (!value.has(key)) return null;
        require(value.get(key).isNumber() && Double.isFinite(value.get(key).doubleValue())); return value.get(key).doubleValue();
    }
    private static String normalize(String type, String model) {
        DeviceType known = DeviceType.fromCode(type);
        if (known == null || model == null || !model.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))
            throw error(ErrorCode.DEVICE_CAPABILITY_NOT_CONFIGURED);
        return known.code();
    }
    private static void require(boolean valid) { if (!valid) throw new IllegalArgumentException("Invalid device capability schema"); }
    private static String hash(String raw) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("Cannot hash capability", e); }
    }
    private static ApiException error(ErrorCode code) { return new ApiException(code, code.name()); }
}
