package com.chh.autosense.core.repair;

import com.chh.autosense.core.device.adapter.AbstractDeviceAdapter;
import com.chh.autosense.core.device.spi.DeviceAdapter;
import com.chh.autosense.domain.entity.Device;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/** Executes a single approved command; workflow services persist and audit its result. */
@Slf4j
@Service
public class RepairExecutor {

    private final com.chh.autosense.core.device.DeviceQueryService queries;
    private final com.chh.autosense.service.device.DeviceAdapterRegistryService adapters;
    private final com.chh.autosense.core.session.DeviceLockService locks;

    public RepairExecutor(com.chh.autosense.core.device.DeviceQueryService queries,
            com.chh.autosense.service.device.DeviceAdapterRegistryService adapters,
            com.chh.autosense.core.session.DeviceLockService locks) {
        this.queries = queries;
        this.adapters = adapters;
        this.locks = locks;
    }

    /** Executes exactly one approved operation. Persistence and fencing belong to CommandExecutionService. */
    public Map<String, Object> executeApproved(com.chh.autosense.graph.state.AssistantState state) {
        var device = queries.device(state);
        var input = state.plan().runtimeInputs();
        String action = java.util.Objects.toString(input.get("action"), "");
        String command;
        Map<String, Object> parameters;
        switch (action) {
            case "setBrightness" -> { command = "set_brightness"; parameters = Map.of("brightness", integer(input, "brightness", 0, 100)); }
            case "setColorTemperature" -> { command = "set_color_temperature"; parameters = Map.of("color_temperature", integer(input, "colorTemperature", 1000, 10000)); }
            default -> throw failure("CAPABILITY_NOT_AVAILABLE", com.chh.autosense.graph.state.ExecutionPlan.Certainty.NOT_SENT);
        }
        var adapter = adapters.adapterOf(device).orElseThrow(() -> failure("CAPABILITY_NOT_AVAILABLE", com.chh.autosense.graph.state.ExecutionPlan.Certainty.NOT_SENT));
        if (!isAllowed(adapter, device, command)) throw failure("COMMAND_NOT_ALLOWED", com.chh.autosense.graph.state.ExecutionPlan.Certainty.NOT_SENT);
        String owner = java.util.UUID.randomUUID().toString();
        if (!locks.tryLock(device.getId(), owner)) throw failure("DEVICE_BUSY", com.chh.autosense.graph.state.ExecutionPlan.Certainty.NOT_SENT);
        try {
            queries.validate(state);
            // Checking the attempt deadline immediately before sending prevents a cancelled worker from starting a new write.
            com.chh.autosense.graph.node.AttemptCalls.limit(java.time.Duration.ofSeconds(1));
            var outcome = adapter.executeRepair(device, command, parameters);
            if (!outcome.success()) throw failure("DEVICE_COMMAND_REJECTED", com.chh.autosense.graph.state.ExecutionPlan.Certainty.FAILED);
            log.info("Device command completed: action={}, result=SUCCEEDED", command);
            return Map.of("deviceRef", device.getId(), "action", action, "parameters", parameters,
                    "verification", "NOT_PERFORMED", "answer", "设备命令已执行成功；尚未复检设备状态，复检需要单独确认查询步骤。");
        } catch (org.springframework.web.client.ResourceAccessException e) {
            throw failure("DEVICE_RESULT_UNKNOWN", com.chh.autosense.graph.state.ExecutionPlan.Certainty.UNKNOWN);
        } finally {
            try { locks.release(device.getId(), owner); }
            catch (RuntimeException e) { log.warn("Device lock release failed: result=LEASE_EXPIRY_REQUIRED"); }
        }
    }

    private static int integer(Map<String, Object> input, String key, int min, int max) {
        Object value = input.get(key);
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue() != n.intValue() || n.intValue() < min || n.intValue() > max)
            throw failure("INVALID_COMMAND_PARAMETERS", com.chh.autosense.graph.state.ExecutionPlan.Certainty.NOT_SENT);
        return n.intValue();
    }

    private static com.chh.autosense.graph.node.StepFailure failure(String code, com.chh.autosense.graph.state.ExecutionPlan.Certainty certainty) {
        return new com.chh.autosense.graph.node.StepFailure(code, certainty);
    }

    private boolean isAllowed(DeviceAdapter adapter, Device device, String actionCode) {
        if (adapter instanceof AbstractDeviceAdapter a) {
            return a.supportedRepairActions(device).contains(actionCode);
        }
        return adapter.supportedRepairActions().contains(actionCode);
    }

}
