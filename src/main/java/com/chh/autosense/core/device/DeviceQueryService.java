package com.chh.autosense.core.device;

import com.chh.autosense.core.device.client.DeviceServiceClient;
import com.chh.autosense.core.session.DeviceLocator;
import com.chh.autosense.domain.entity.Device;
import com.chh.autosense.graph.node.*;
import com.chh.autosense.graph.state.*;
import com.chh.autosense.mapper.*;
import com.chh.autosense.service.device.DeviceAdapterRegistryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;

/** Local resolution is read-only; remote reads run only inside an approved QuerySubGraph. */
@Service
@RequiredArgsConstructor
public class DeviceQueryService {
    private static final Set<String> STATE_FIELDS = Set.of("brightness", "color_temperature", "color", "power", "temperature",
            "humidity", "voltage", "current", "running_status", "hardware_fault", "error", "online");
    private final DeviceMapper devices;
    private final UserMapper users;
    private final DeviceLocator locator;
    private final DeviceAdapterRegistryService adapters;
    private final DeviceServiceClient client;

    public Map<String, Object> resolve(AssistantState state) {
        account(state.request().userId());
        var input = state.plan().runtimeInputs();
        if ("list".equals(input.get("action"))) return Map.of("deviceRefs", devices.selectMine(state.request().userId()).stream().map(Device::getId).toList());
        if (input.get("deviceRef") instanceof Number id) return target(owned(state.request().userId(), id.longValue()));
        String type = input.get("deviceType") instanceof String value ? value : null;
        String model = input.get("model") instanceof String value ? value : null;
        var candidates = locator.findCandidates(state.request().userId(), type, model);
        String hint = Objects.toString(input.get("clarification"), state.plan().step().targetHint());
        var named = hint == null ? List.<Device>of() : candidates.stream().filter(d -> d.getName() != null && hint.contains(d.getName())).toList();
        if (named.size() == 1) return target(named.getFirst());
        if (candidates.size() == 1) return target(candidates.getFirst());
        if (input.containsKey("clarification")) {
            var selected = locator.pickFromCandidates(candidates, hint);
            if (selected != null) return target(selected);
        }
        return Map.of();
    }

    public void validate(AssistantState state) {
        account(state.request().userId());
        var target = state.<AssistantState.DeviceContext>value(AssistantState.DEVICE).orElseThrow().resolved();
        if (target.get("deviceRefs") instanceof List<?> ids) {
            for (Object id : ids) { if (!(id instanceof Number number)) throw new SecurityException("Invalid target"); owned(state.request().userId(), number.longValue()); }
        } else device(state);
    }
    public Device device(AssistantState state) {
        account(state.request().userId());
        var target = state.<AssistantState.DeviceContext>value(AssistantState.DEVICE).orElseThrow().resolved();
        if (!(target.get("deviceRef") instanceof Number id)) throw new SecurityException("Device target is required");
        var device = owned(state.request().userId(), id.longValue());
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
        return Map.of("deviceRef", device.getId(), "name", Objects.toString(device.getName(), ""),
                "deviceType", Objects.toString(device.getDeviceTypeCode(), ""), "model", Objects.toString(device.getDeviceModelCode(), ""),
                "bindingHash", PlanValidator.digest(List.of(Objects.toString(device.getSimulatorDeviceId(), ""),
                        Objects.toString(device.getDeviceTypeCode(), ""), Objects.toString(device.getDeviceModelCode(), ""))));
    }

    public Map<String, Object> query(AssistantState state) {
        validate(state);
        String action = Objects.toString(state.plan().runtimeInputs().get("action"), "state");
        if (!Set.of("list", "state", "diagnostic_snapshot").contains(action)) throw new StepFailure("INVALID_QUERY_ACTION", ExecutionPlan.Certainty.NOT_SENT);
        var target = state.<AssistantState.DeviceContext>value(AssistantState.DEVICE).orElseThrow().resolved();
        if (action.equals("list")) {
            if (!(target.get("deviceRefs") instanceof List<?> ids)) throw new SecurityException("List approval scope is missing");
            var listed = ids.stream().map(id -> target(owned(state.request().userId(), ((Number) id).longValue()))).toList();
            return Map.of("devices", listed, "observedAt", Instant.now().toString(), "answer", "已查询确认范围内的设备，共" + listed.size() + "台。\n" + listed);
        }
        var device = device(state);
        Map<String, Object> raw;
        if (action.equals("diagnostic_snapshot")) {
            var adapter = adapters.adapterOf(device).orElseThrow(() -> new StepFailure("CAPABILITY_NOT_AVAILABLE", ExecutionPlan.Certainty.NOT_SENT));
            raw = adapter.getDiagnostics(device);
        } else raw = client.getDeviceState(device.getSimulatorDeviceId());
        var values = new LinkedHashMap<String, Object>();
        if (raw != null) raw.forEach((key, value) -> { if (STATE_FIELDS.contains(key) && value != null) values.put(key, value); });
        var result = new LinkedHashMap<>(target(device));
        result.putAll(values);
        if (values.containsKey("color_temperature")) result.put("colorTemperature", values.get("color_temperature"));
        String observedAt = Instant.now().toString(); result.put("observedAt", observedAt);
        result.put("state", values);
        result.put("evidence", Map.of("deviceRef", device.getId(), "deviceType", adapters.diagnosticTypeOf(device).orElse(""),
                "model", Objects.toString(device.getDeviceModelCode(), ""), "observedAt", observedAt, "state", values));
        result.put("answer", "设备“" + device.getName() + "”的已确认查询结果：\n" + values + "\n采集时间：" + observedAt);
        return StateData.freeze(result);
    }
}
