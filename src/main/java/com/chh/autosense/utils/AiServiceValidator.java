package com.chh.autosense.utils;

import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI Service 接口的本地装配校验:读取方法级 fromResource 注解,经 classpath 流检查
 * /prompt 资源,核对变量集合与方法 @V 绑定完全一致,并用合成非空样本渲染;任一失败
 * 抛出 IllegalStateException 中止装配,零模型请求,绝不回落内联模板或 mock 行为。
 * 异常消息与 ERROR 日志只携带 service/method/resource 路径与英文 reasonCode,
 * 不包含模板内容或渲染值。
 */
@Slf4j
public final class AiServiceValidator {

    private static final Pattern VARIABLE = Pattern.compile("\\{\\{([^{}]*)}}");
    private static final Pattern STRAY_OPEN = Pattern.compile("\\{\\{");

    private AiServiceValidator() {
    }

    public static void validateServices(List<Class<?>> services) {
        if (!StandardCharsets.UTF_8.equals(Charset.defaultCharset())) {
            throw failure(null, null, null, "DEFAULT_CHARSET_NOT_UTF_8");
        }
        Map<String, String> resources = new HashMap<>();
        for (Class<?> service : services) {
            for (Method method : service.getDeclaredMethods()) {
                validateMethod(service, method, resources);
            }
        }
    }

    private static void validateMethod(Class<?> service, Method method, Map<String, String> resources) {
        SystemMessage system = method.getAnnotation(SystemMessage.class);
        UserMessage user = method.getAnnotation(UserMessage.class);
        if (system == null || user == null) {
            throw failure(service, method, null, "ANNOTATION_MISSING");
        }
        if (hasInline(system.value()) || hasInline(user.value())) {
            throw failure(service, method, null, "INLINE_VALUE_NOT_ALLOWED");
        }
        String systemTemplate = load(service, method, system.fromResource(), resources);
        String userTemplate = load(service, method, user.fromResource(), resources);
        Map<String, Integer> systemVariables = variables(service, method, system.fromResource(), systemTemplate);
        if (!systemVariables.isEmpty()) {
            throw failure(service, method, system.fromResource(), "SYSTEM_TEMPLATE_HAS_VARIABLES");
        }
        Set<String> bound = new HashSet<>();
        for (Parameter parameter : method.getParameters()) {
            V annotation = parameter.getAnnotation(V.class);
            if (annotation == null || annotation.value().isBlank()) {
                throw failure(service, method, null, "PARAMETER_MISSING_V");
            }
            bound.add(annotation.value());
        }
        Map<String, Integer> userVariables = variables(service, method, user.fromResource(), userTemplate);
        for (Map.Entry<String, Integer> entry : userVariables.entrySet()) {
            if (entry.getValue() != 1) {
                throw failure(service, method, user.fromResource(), "VARIABLE_DUPLICATED");
            }
        }
        if (!userVariables.keySet().equals(bound)) {
            throw failure(service, method, user.fromResource(), "VARIABLE_MISMATCH");
        }
        renderSample(service, method, user.fromResource(), userTemplate, bound);
    }

    private static boolean hasInline(String[] value) {
        for (String line : value) {
            if (line != null && !line.isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static String load(Class<?> service, Method method, String path, Map<String, String> resources) {
        if (path == null || !path.startsWith("/prompt/") || path.contains("..")) {
            throw failure(service, method, path, "RESOURCE_PATH_INVALID");
        }
        String cached = resources.get(path);
        if (cached != null) {
            return cached;
        }
        byte[] bytes;
        try (InputStream stream = service.getResourceAsStream(path)) {
            if (stream == null) {
                throw failure(service, method, path, "RESOURCE_MISSING");
            }
            bytes = stream.readAllBytes();
        } catch (IOException e) {
            throw failure(service, method, path, "RESOURCE_UNREADABLE");
        }
        if (bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF) {
            throw failure(service, method, path, "RESOURCE_HAS_BOM");
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw failure(service, method, path, "RESOURCE_NOT_UTF_8");
        }
        if (text.isBlank()) {
            throw failure(service, method, path, "RESOURCE_BLANK");
        }
        resources.put(path, text);
        return text;
    }

    private static Map<String, Integer> variables(Class<?> service, Method method, String resource,
                                                  String template) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Matcher matcher = VARIABLE.matcher(template);
        int matched = 0;
        while (matcher.find()) {
            matched++;
            String name = matcher.group(1);
            if (name.isEmpty() || !name.equals(name.trim())
                    || name.chars().anyMatch(Character::isWhitespace)) {
                throw failure(service, method, resource, "VARIABLE_MALFORMED");
            }
            counts.merge(name, 1, Integer::sum);
        }
        if (count(STRAY_OPEN, template) != matched) {
            throw failure(service, method, resource, "VARIABLE_MALFORMED");
        }
        return counts;
    }

    private static int count(Pattern pattern, String template) {
        int found = 0;
        Matcher matcher = pattern.matcher(template);
        while (matcher.find()) {
            found++;
        }
        return found;
    }

    private static void renderSample(Class<?> service, Method method, String resource,
                                     String template, Set<String> bound) {
        Map<String, Object> sample = new HashMap<>();
        bound.forEach(name -> sample.put(name, "sample-" + name));
        String rendered;
        try {
            rendered = PromptTemplate.from(template).apply(sample).text();
        } catch (RuntimeException e) {
            throw failure(service, method, resource, "SAMPLE_RENDER_FAILED");
        }
        if (rendered.contains("{{")) {
            throw failure(service, method, resource, "SAMPLE_RENDER_INCOMPLETE");
        }
    }

    private static IllegalStateException failure(Class<?> service, Method method, String resource, String reasonCode) {
        String message = "AI prompt resource validation failed: service=%s, method=%s, resource=%s, reasonCode=%s"
                .formatted(service == null ? "unknown" : service.getName(),
                        method == null ? "unknown" : method.getName(),
                        resource == null ? "none" : resource, reasonCode);
        log.error("AI prompt resource validation failed: service={}, method={}, resource={}, reasonCode={}",
                service == null ? "unknown" : service.getName(),
                method == null ? "unknown" : method.getName(),
                resource == null ? "none" : resource, reasonCode);
        return new IllegalStateException(message);
    }
}
