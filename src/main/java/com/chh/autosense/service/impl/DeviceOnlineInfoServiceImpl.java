package com.chh.autosense.service.impl;

import com.chh.autosense.ai.model.DeviceAttributeModels;
import com.chh.autosense.ai.model.DeviceOnlineInfo;
import com.chh.autosense.config.DeviceQueryProperties;
import com.chh.autosense.config.DeviceServiceProperties;
import com.chh.autosense.domain.dto.DeviceCapabilityDefinition;
import com.chh.autosense.domain.dto.DevicePropertiesResponse;
import com.chh.autosense.exception.ApiException;
import com.chh.autosense.exception.ErrorCode;
import com.chh.autosense.graph.node.AttemptCalls;
import com.chh.autosense.service.DeviceOnlineInfoService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.*;
import java.util.Map;
import java.util.concurrent.*;

/** Confirmed read-only HTTP access; retry and authorization belong to the workflow. */
@Service
@Slf4j
@ConditionalOnProperty(prefix = "autosense.device-query", name = "runtime-provider", havingValue = "real")
public class DeviceOnlineInfoServiceImpl implements DeviceOnlineInfoService {
    private static final ObjectMapper JSON = JsonMapper.builder()
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    private final String baseUrl;
    private final Duration timeout;
    private final RuntimeIdentity identity;
    private final RestClient.Builder builder;

    public DeviceOnlineInfoServiceImpl(DeviceQueryProperties query, DeviceServiceProperties service, RestClient.Builder builder) {
        this.baseUrl = service.normalizedBaseUrl();
        this.timeout = Duration.ofSeconds(service.timeoutSeconds() == null ? 10 : service.timeoutSeconds());
        this.identity = new RuntimeIdentity("real", query.externalSource(), service.endpointHash());
        this.builder = builder.clone();
    }

    @Override public RuntimeIdentity runtimeIdentity() { return identity; }
    @Override public void validateModel(DeviceCapabilityDefinition definition) { DeviceAttributeModels.validateModel(definition); }

    @Override public DeviceOnlineInfo read(DeviceCapabilityDefinition definition, Invocation invocation) {
        long start = System.nanoTime(); String result = "FAILED", code = "DEVICE_SERVICE_UNAVAILABLE";
        log.info("Device runtime call started: operation=getProperties, provider=real, source={}", identity.source());
        try {
            if (invocation.sn() == null || !invocation.sn().matches("^[A-Z0-9]{4}[0-9]{9}$"))
                throw new ApiException(ErrorCode.INVALID_ARGUMENT, "Invalid device serial format");
            if (!invocation.online()) throw new ApiException(ErrorCode.DEVICE_OFFLINE, "Device is offline");
            validateModel(definition);
            Duration budget = remaining(invocation);
            var http = HttpClient.newBuilder().connectTimeout(budget).followRedirects(HttpClient.Redirect.NEVER).build();
            try {
                var factory = new JdkClientHttpRequestFactory(http); factory.setReadTimeout(budget);
                // The trusted base path is retained byte-for-byte; only the validated serial fills the path template.
                String path = org.springframework.web.util.UriComponentsBuilder.fromPath("/device/{sn}/get")
                        .buildAndExpand(invocation.sn()).encode().toUriString();
                var value = builder.clone().requestFactory(factory).build().post().uri(URI.create(baseUrl + path))
                        .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON).body(new byte[0])
                        .exchange((request, response) -> {
                            int status = response.getStatusCode().value();
                            if (status < 200 || status >= 300) {
                                var error = status == 400 ? ErrorCode.INVALID_ARGUMENT : status == 404 ? ErrorCode.DEVICE_UNREACHABLE : ErrorCode.DEVICE_SERVICE_UNAVAILABLE;
                                log.warn("Device runtime response rejected: operation=getProperties, provider=real, source={}, httpStatus={}, errorCode={}", identity.source(), status, error);
                                throw new ApiException(error, "External device request failed");
                            }
                            var envelope = JSON.readValue(response.getBody(), DevicePropertiesResponse.class);
                            if (envelope == null || !Boolean.TRUE.equals(envelope.success())
                                    || !invocation.sn().equals(envelope.sn())
                                    || envelope.properties() == null || !envelope.properties().isObject())
                                throw new DeviceAttributeModels.InvalidAttributes(Map.of());
                            // Definition is selected by the owned local device, never by external ID/type/model fields.
                            return DeviceAttributeModels.decode(definition, "get_properties", envelope.properties());
                        });
                remaining(invocation); // A late response is never published as a successful read.
                result = "OK"; code = "OK";
                return new DeviceOnlineInfo(identity.source(), Instant.now(), definition.contentHash(), Map.of("get_properties", value));
            } finally { http.shutdownNow(); }
        } catch (ApiException e) { code = e.errorCode().name(); throw e; }
        catch (Exception e) {
            if (isTimeout(e)) {
                result = "TIMEOUT"; code = "REQUEST_TIMEOUT";
                // Keep a recognizable, sanitized cause without the client's URI-bearing exception text.
                throw new CompletionException(new TimeoutException("Device runtime request timed out"));
            }
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof com.fasterxml.jackson.core.JsonProcessingException) {
                    code = "DEVICE_ONLINE_INFO_INVALID"; throw new DeviceAttributeModels.InvalidAttributes(Map.of());
                }
            }
            throw new ApiException(ErrorCode.DEVICE_SERVICE_UNAVAILABLE, "External device service is unavailable");
        } finally {
            log.info("Device runtime call completed: operation=getProperties, provider=real, source={}, result={}, errorCode={}, elapsedMs={}",
                    identity.source(), result, code, (System.nanoTime() - start) / 1_000_000);
        }
    }

    private Duration remaining(Invocation invocation) {
        if (invocation.deadline() == null) throw new ApiException(ErrorCode.INVALID_ARGUMENT, "Device request deadline is required");
        Duration remaining = Duration.between(Instant.now(), invocation.deadline());
        if (remaining.isNegative() || remaining.isZero()) throw new CompletionException(new TimeoutException("Device request deadline exceeded"));
        return AttemptCalls.limit(remaining.compareTo(timeout) < 0 ? remaining : timeout);
    }
    private static boolean isTimeout(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause())
            if (cause instanceof TimeoutException || cause instanceof java.net.SocketTimeoutException || cause instanceof java.net.http.HttpTimeoutException) return true;
        return false;
    }
}
