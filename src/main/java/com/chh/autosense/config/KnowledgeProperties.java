package com.chh.autosense.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.text.Normalizer;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

@ConfigurationProperties("autosense.knowledge")
public record KnowledgeProperties(
        @DefaultValue Documents documents,
        @DefaultValue Retrieval retrieval,
        @DefaultValue ServiceCache serviceCache,
        @DefaultValue Store store,
        Map<String, String> typeAliases,
        Map<String, String> brandAliases,
        Map<String, String> modelAliases) {

    public KnowledgeProperties {
        typeAliases = aliases(typeAliases, "[a-z][a-z0-9_]*");
        brandAliases = aliases(brandAliases, "[A-Za-z0-9_]+");
        modelAliases = aliases(modelAliases, "[A-Za-z0-9][A-Za-z0-9._-]*");
    }

    private static Map<String, String> aliases(Map<String, String> input, String pattern) {
        if (input == null) return Map.of();
        Map<String, String> result = new HashMap<>();
        input.forEach((alias, canonical) -> {
            if (alias == null || alias.isBlank() || canonical == null || !canonical.matches(pattern)
                    || alias.codePoints().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Invalid knowledge alias configuration");
            }
            String key = Normalizer.normalize(alias.trim(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
            String prior = result.putIfAbsent(key, canonical);
            if (prior != null && !prior.equals(canonical)) {
                throw new IllegalArgumentException("Ambiguous knowledge alias configuration");
            }
        });
        return Map.copyOf(result);
    }

    public record Documents(
            @DefaultValue("classpath*:document/**/*.md") String location,
            @DefaultValue("33554432") long maxTotalBytes,
            @DefaultValue("10000") int maxSegments,
            @DefaultValue("300") int startupTimeoutSeconds,
            @DefaultValue("1000") int maxSegmentChars,
            @DefaultValue("150") int overlapChars) {
        public Documents {
            if (!"classpath*:document/**/*.md".equals(location) || maxTotalBytes <= 0
                    || maxTotalBytes > Integer.MAX_VALUE - 1L || maxSegments <= 0
                    || startupTimeoutSeconds <= 0 || maxSegmentChars <= 0
                    || overlapChars < 0 || overlapChars >= maxSegmentChars) {
                throw new IllegalArgumentException("Invalid knowledge document configuration");
            }
        }
    }

    public record Retrieval(@DefaultValue("4") int topK, @DefaultValue("0.75") double minScore) {
        public Retrieval {
            if (topK <= 0 || !Double.isFinite(minScore) || minScore < 0 || minScore > 1) {
                throw new IllegalArgumentException("Invalid knowledge retrieval configuration");
            }
        }
    }

    public record ServiceCache(@DefaultValue("1000") long maximumSize,
                               @DefaultValue("30m") Duration expireAfterAccess) {
        public ServiceCache {
            if (maximumSize <= 0 || expireAfterAccess == null || expireAfterAccess.isNegative()
                    || expireAfterAccess.isZero()) throw new IllegalArgumentException("Invalid AI service cache configuration");
        }
    }

    public record Store(@DefaultValue("memory") String type) {
        public Store {
            if (!"memory".equals(type)) throw new IllegalArgumentException("Unsupported knowledge store type");
        }
    }
}
