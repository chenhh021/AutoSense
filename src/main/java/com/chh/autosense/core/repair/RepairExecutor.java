package com.chh.autosense.core.repair;

import com.chh.autosense.core.device.adapter.AbstractDeviceAdapter;
import com.chh.autosense.core.device.spi.DeviceAdapter;
import com.chh.autosense.domain.enums.ActionResult;
import com.chh.autosense.domain.entity.Device;
import com.chh.autosense.domain.entity.RepairActionLog;
import com.chh.autosense.mapper.RepairActionLogMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 修复执行器(FR-008/FR-017,T025):仅允许适配器白名单动作;
 * 失败即停,不自动重试;执行结果写 repair_action_log。
 */
@Slf4j
@Service
public class RepairExecutor {

    private final RepairActionLogMapper actionLogMapper;
    private final ObjectMapper objectMapper;

    public RepairExecutor(RepairActionLogMapper actionLogMapper, ObjectMapper objectMapper) {
        this.actionLogMapper = actionLogMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行白名单动作(FR-008/FR-017)。注意:确认门在编排层(CONFIRMING_REPAIR,
     * 2026-08-22 起覆盖全部改变状态的操作);这里只做白名单校验与执行。
     */
    public DeviceAdapter.RepairOutcome execute(Long sessionId, Device device,
                                               DeviceAdapter adapter, String actionCode,
                                               Map<String, Object> params) {
        if (!isAllowed(adapter, device, actionCode)) {
            writeLog(sessionId, actionCode, params, ActionResult.SKIPPED, "动作不在白名单");
            return new DeviceAdapter.RepairOutcome(false, "动作不在白名单: " + actionCode);
        }
        DeviceAdapter.RepairOutcome outcome;
        try {
            outcome = adapter.executeRepair(device, actionCode,
                    params == null ? Map.of() : params);
        } catch (Exception e) {
            // 失败即停(FR-017):捕获后如实记录,不重试、不抛出中断编排
            log.warn("修复动作执行异常 session={} action={}: {}", sessionId, actionCode, e.getMessage());
            outcome = new DeviceAdapter.RepairOutcome(false, "执行异常: " + e.getMessage());
        }
        writeLog(sessionId, actionCode, params,
                outcome.success() ? ActionResult.SUCCESS : ActionResult.FAILED, outcome.message());
        return outcome;
    }

    private boolean isAllowed(DeviceAdapter adapter, Device device, String actionCode) {
        if (adapter instanceof AbstractDeviceAdapter a) {
            return a.supportedRepairActions(device).contains(actionCode);
        }
        return adapter.supportedRepairActions().contains(actionCode);
    }

    private void writeLog(Long sessionId, String actionCode, Map<String, Object> params,
                          ActionResult result, String message) {
        RepairActionLog logEntry = new RepairActionLog();
        logEntry.setSessionId(sessionId);
        logEntry.setActionCode(actionCode);
        try {
            logEntry.setParams(params == null ? null : objectMapper.writeValueAsString(params));
        } catch (Exception ignored) {
            logEntry.setParams(null);
        }
        logEntry.setResult(result.name());
        logEntry.setMessage(message);
        actionLogMapper.insert(logEntry);
    }
}
