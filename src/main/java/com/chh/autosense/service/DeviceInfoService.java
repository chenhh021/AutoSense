package com.chh.autosense.service;

import com.chh.autosense.ai.model.*;
import com.chh.autosense.config.DeviceQueryProperties;
import com.chh.autosense.core.security.*;
import com.chh.autosense.domain.dto.DeviceCapabilityDefinition;
import com.chh.autosense.exception.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceInfoService {
    private final DeviceListService lists;
    private final DeviceCapabilityService capabilities;
    private final DeviceOnlineInfoService online;
    private final DeviceToolAuthorizationService authorization;
    private final DeviceQueryProperties properties;

    public DeviceInfoResult execute(DeviceInfoRequest request, DeviceToolExecutionContext context) {
        var sns = normalize(request);
        authorization.requireQueryScope(context, sns);
        var results = sns.stream().map(sn -> item(sn, context)).toList();
        long errors = results.stream().filter(item -> !item.errorCode().equals("OK")).count();
        String code = errors == 0 ? "OK" : results.size() == 1 ? results.getFirst().errorCode() : "PARTIAL_FAILURE";
        var sources = results.stream().filter(item -> item.onlineInfo() != null).map(item -> item.onlineInfo().source()).distinct().toList();
        log.info("Device information completed: count={}, errorCount={}, result={}, runtimeSource={}", results.size(), errors, code, String.join(",", sources));
        return new DeviceInfoResult(results, code);
    }

    private List<String> normalize(DeviceInfoRequest request) {
        if (request == null || request.sns() == null || request.sns().isEmpty()) throw invalid();
        var unique = new LinkedHashSet<String>();
        for (String sn : request.sns()) {
            if (sn == null || sn.isBlank() || sn.length() > 13 || !sn.equals(sn.strip())) throw invalid();
            unique.add(sn);
            if (unique.size() > properties.maxSns()) throw invalid();
        }
        return List.copyOf(unique);
    }

    private ApiException invalid() {
        return new ApiException(ErrorCode.INVALID_ARGUMENT, "Invalid device SN list");
    }

    private DeviceInfoItem item(String sn, DeviceToolExecutionContext context) {
        DeviceBasicInfo basic;
        try {
            var local = lists.localBySn(context.actor(), sn);
            Boolean saved = context.snapshotOnline(sn);
            boolean isOnline = saved != null ? saved : "OK".equals(lists.onlineStatus(context.actor(), sn, context.deadline()).errorCode());
            basic = new DeviceBasicInfo(local.id(), local.name(), local.sn(), local.deviceTypeCode(), local.deviceModelCode(), isOnline, null);
        } catch (ApiException e) {
            return new DeviceInfoItem(sn, null, null, null, e.errorCode().name(), Map.of());
        }
        DeviceCapabilityDefinition definition = null;
        var errors = new LinkedHashMap<String, String>();
        try {
            definition = capabilities.get(basic.deviceType(), basic.deviceModel());
        } catch (ApiException e) {
            errors.put("capabilityInfo", e.errorCode().name());
        }
        var capability = definition == null ? null : new DeviceCapabilityInfo(definition.deviceType(), definition.deviceModel(), definition.rawJson(), definition.contentHash());
        if (!basic.online()) return new DeviceInfoItem(sn, basic, null, capability, "DEVICE_OFFLINE", errors);
        if (definition == null) return new DeviceInfoItem(sn, basic, null, null, errors.get("capabilityInfo"), errors);
        // Recheck persisted approval, lease and binding immediately before runtime generation.
        authorization.requireProfile(context, sn, definition.contentHash());
        try {
            String key = context.invocationKey(sn);
            var generated = online.read(definition, new DeviceOnlineInfoService.Invocation(key, snIndependentStep(key), generatedAt(), true,
                    basic.sn(), context.deadline()));
            return new DeviceInfoItem(sn, basic, generated, capability, "OK", errors);
        } catch (ApiException e) {
            errors.put("onlineInfo", e.errorCode().name());
            if (e instanceof DeviceAttributeModels.InvalidAttributes invalid) errors.putAll(invalid.diagnostics());
            return new DeviceInfoItem(sn, basic, null, capability, e.errorCode().name(), errors);
        }
    }

    private static String snIndependentStep(String key) {
        return key.substring(key.lastIndexOf(':') + 1);
    }

    private static Instant generatedAt() {
        com.chh.autosense.graph.node.AttemptCalls calls;
        try {
            calls = com.chh.autosense.graph.node.AttemptCalls.current();
        } catch (IllegalStateException noGraph) {
            return Instant.now();
        }
        try {
            return Instant.parse((String) calls.call("device-generation-time", ignored -> Instant.now().toString()));
        } catch (Exception e) {
            throw new java.util.concurrent.CompletionException(e);
        }
    }

}
