package com.chh.autosense.core.device;

import com.chh.autosense.ai.model.*;
import com.chh.autosense.service.DeviceInfoService;
import com.chh.autosense.core.security.*;
import com.chh.autosense.domain.entity.Device;
import com.chh.autosense.domain.enums.PlanStepType;
import com.chh.autosense.domain.dto.DeviceCapabilityDefinition;
import com.chh.autosense.graph.node.*;
import com.chh.autosense.graph.state.*;
import com.chh.autosense.mapper.*;
import com.chh.autosense.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

/** Exact model validation precedes approval; runtime reads require a persisted approval scope. */
@Service
@RequiredArgsConstructor
public class DeviceQueryService {
    private final DeviceMapper devices;
    private final UserMapper users;
    private final DeviceCapabilityService capabilities;
    private final DeviceOnlineInfoService online;
    private final DeviceInfoService deviceInfo;
    private final DeviceToolAuthorizationService authorization;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public Map<String, Object> resolve(AssistantState state) {
        account(state.request().userId());
        var input = state.plan().runtimeInputs();
        if (Set.of("list", "metadata").contains(Objects.toString(input.get("action"), "")))
            throw new StepFailure("INVALID_QUERY_ACTION", ExecutionPlan.Certainty.NOT_SENT);
        if (!(input.get("deviceRef") instanceof Number id) || id.longValue() <= 0
                || id.doubleValue() != id.longValue())
            throw new StepFailure("DEVICE_TARGET_REQUIRED", ExecutionPlan.Certainty.NOT_SENT);
        var device = owned(state.request().userId(), id.longValue());
        validateSnapshot(state, device);
        return targetFor(state, device);
    }

    private void validateSnapshot(AssistantState state, Device device) {
        DeviceBasicInfo saved;
        try { saved = state.<AssistantState.DeviceContext>value(AssistantState.DEVICE).orElseThrow().requireDevice(device.getId()); }
        catch (IllegalArgumentException e) { throw new StepFailure("DEVICE_TARGET_INVALID", ExecutionPlan.Certainty.NOT_SENT); }
        if (!Objects.equals(saved.sn(), device.getSn()) || !Objects.equals(saved.deviceType(), device.getDeviceTypeCode())
                || !Objects.equals(saved.deviceModel(), device.getDeviceModelCode()))
            throw new StepFailure("DEVICE_BINDING_CHANGED", ExecutionPlan.Certainty.NOT_SENT);
        var inputs = state.plan().runtimeInputs();
        if (inputs.containsKey("deviceType") && !Objects.equals(inputs.get("deviceType"), saved.deviceType())
                || inputs.containsKey("model") && !Objects.equals(inputs.get("model"), saved.deviceModel()))
            throw new StepFailure("DEVICE_TARGET_INVALID", ExecutionPlan.Certainty.NOT_SENT);
    }

    private Map<String, Object> targetFor(AssistantState state, Device device) {
        var target = new LinkedHashMap<>(target(device));
        if (state.plan().step().type() == PlanStepType.DEVICE_QUERY) {
            var definition = definition(device); online.validateModel(definition);
            if (state.plan().runtimeInputs().get("fields") instanceof List<?> fields)
                for (Object field : fields) definition.field(fieldPath(Objects.toString(field)));
            target.put("capabilityHash", definition.contentHash()); target.put("getScope", definition.getScope());
            var identity = online.runtimeIdentity();
            target.put("runtimeProvider", identity.provider()); target.put("runtimeSource", identity.source());
            target.put("runtimeEndpointHash", identity.endpointHash()); target.put("snapshotKind", "FULL_GET_SNAPSHOT");
        }
        return StateData.freeze(target);
    }
    public DeviceCapabilityDefinition definition(Device device) {
        return capabilities.get(device.getDeviceTypeCode(), device.getDeviceModelCode());
    }
    public void validate(AssistantState state) {
        var device = device(state);
        if (state.plan().step().type() == PlanStepType.DEVICE_QUERY) {
            var definition = definition(device); online.validateModel(definition);
            if (state.plan().runtimeInputs().get("fields") instanceof List<?> fields)
                for (Object field : fields) definition.field(fieldPath(Objects.toString(field)));
        }
    }
    public Device device(AssistantState state) {
        account(state.request().userId());
        var target = state.<AssistantState.DeviceContext>value(AssistantState.DEVICE).orElseThrow().resolved();
        if (!(target.get("deviceRef") instanceof Number id)) throw new SecurityException("Device target is required");
        var device = owned(state.request().userId(), id.longValue());
        validateSnapshot(state, device);
        if (target.get("bindingHash") != null && !target.get("bindingHash").equals(target(device).get("bindingHash")))
            throw new SecurityException("Device binding changed after confirmation");
        return device;
    }
    private void account(long userId) {
        var account = users.selectByIdIncludingDeleted(userId);
        if (account == null || account.getIsDelete() != null && account.getIsDelete() != 0) throw new SecurityException("Account is unavailable");
    }
    private Device owned(long userId, long id) {
        var device = devices.selectOneById(id);
        if (device == null || device.getUserId() == null || device.getUserId() != userId) throw new SecurityException("Device is unavailable or ownership changed");
        return device;
    }
    private Map<String, Object> target(Device device) {
        return Map.of("deviceRef", device.getId(), "name", Objects.toString(device.getName(), ""), "sn", Objects.toString(device.getSn(), ""),
                "deviceType", Objects.toString(device.getDeviceTypeCode(), ""), "model", Objects.toString(device.getDeviceModelCode(), ""),
                "bindingHash", PlanValidator.digest(List.of(Objects.toString(device.getSimulatorDeviceId(), ""),
                        Objects.toString(device.getDeviceTypeCode(), ""), Objects.toString(device.getDeviceModelCode(), ""))));
    }

    public Map<String, Object> query(AssistantState state) {
        validate(state);
        String action = Objects.toString(state.plan().runtimeInputs().get("action"), "state");
        if (!Set.of("state", "diagnostic_snapshot").contains(action)) throw new StepFailure("INVALID_QUERY_ACTION", ExecutionPlan.Certainty.NOT_SENT);
        var device = device(state);
        var context = authorization.query(state, Instant.now().plus(AttemptCalls.limit(Duration.ofSeconds(30))));
        Map<String, Object> result;
        try {
            AttemptCalls calls;
            try { calls = AttemptCalls.current(); } catch (IllegalStateException noGraph) { calls = null; }
            if (calls == null) result = read(device, context);
            else {
                @SuppressWarnings("unchecked") var saved = (Map<String, Object>) calls.call("device-information", ignored -> read(device, context));
                result = saved;
            }
        } catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new java.util.concurrent.CompletionException(e); }
        if (!"OK".equals(result.get("errorCode"))) throw new StepFailure(Objects.toString(result.get("errorCode")), ExecutionPlan.Certainty.NOT_SENT, result);
        if (PlanRouter.isMock(result) && state.plan().executionPlan().steps().stream().anyMatch(step ->
                step.type() == PlanStepType.DEVICE_CONTROL && state.plan().step().dependsOn().contains(step.stepId())))
            throw new StepFailure("MOCK_EVIDENCE_NOT_ALLOWED", ExecutionPlan.Certainty.NOT_SENT, result);
        return display(state, result);
    }

    private Map<String, Object> read(Device device, DeviceToolExecutionContext context) {
        var response = deviceInfo.execute(new DeviceInfoRequest(List.of(device.getSn())), context);
        var item = response.devices().getFirst();
        Map<String, Object> result = json.convertValue(item, new com.fasterxml.jackson.core.type.TypeReference<>() { });
        result.put("deviceRef", device.getId()); result.put("deviceType", device.getDeviceTypeCode()); result.put("model", device.getDeviceModelCode());
        if (item.onlineInfo() != null) {
            Map<String, Object> values = json.convertValue(item.onlineInfo().getResults(), new com.fasterxml.jackson.core.type.TypeReference<>() { });
            result.put("source", item.onlineInfo().source()); result.put("observedAt", item.onlineInfo().generatedAt().toString());
            result.put("evidence", Map.of("deviceRef", device.getId(), "deviceType", device.getDeviceTypeCode(), "model", device.getDeviceModelCode(),
                    "source", item.onlineInfo().source(), "getResults", values, "observedAt", item.onlineInfo().generatedAt().toString()));
            if (values.get("get_properties") instanceof Map<?, ?> fields) {
                for (String name : List.of("brightness", "power", "temperature", "online")) if (fields.containsKey(name)) result.put(name, fields.get(name));
                if (fields.containsKey("color_temperature")) result.put("colorTemperature", fields.get("color_temperature"));
            }
        }
        return StateData.freeze(result);
    }

    private Map<String, Object> display(AssistantState state, Map<String, Object> result) {
        // Render against the version actually read, even if the resource changes after the read.
        var profile = (Map<?, ?>) result.get("capabilityInfo");
        var definition = com.chh.autosense.service.impl.DeviceCapabilityServiceImpl.parse(
                profile.get("deviceType").toString(), profile.get("deviceModel").toString(), profile.get("rawJson").toString());
        List<String> fields = state.plan().runtimeInputs().get("fields") instanceof List<?> selected
                ? selected.stream().map(value -> fieldPath(value.toString())).toList()
                : definition.getScope().stream().filter(path -> path.split("\\.").length >= 3)
                    .filter(path -> !definition.field(path).dataType().equals("OBJECT")).toList();
        var shown = new LinkedHashMap<String, Object>();
        for (String field : fields) {
            var node = definition.field(field);
            shown.put(field, Map.of("value", PlanRouter.publicValue(result, field), "unit", node.unit()));
        }
        var copy = new LinkedHashMap<>(result); copy.put("displayValues", shown);
        return StateData.freeze(copy);
    }
    public static String fieldPath(String field) {
        if (field.startsWith("getResults.")) return field;
        return "getResults.get_properties." + (field.equals("colorTemperature") ? "color_temperature" : field);
    }
}
