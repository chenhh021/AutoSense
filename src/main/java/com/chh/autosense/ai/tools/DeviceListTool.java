package com.chh.autosense.ai.tools;

import cn.hutool.json.JSONObject;
import com.chh.autosense.ai.model.*;
import com.chh.autosense.config.DeviceQueryProperties;
import com.chh.autosense.core.security.AuthUser;
import com.chh.autosense.service.DeviceListService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

@Component
@lombok.extern.slf4j.Slf4j
public class DeviceListTool extends BaseTool {
    private final DeviceListService service;
    private final DeviceQueryProperties properties;

    public DeviceListTool(DeviceListService service, DeviceQueryProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @Tool(name = "deviceList", value = "获取当前用户已绑定设备的基础信息列表")
    public String deviceList(@P("用户ID") String userId) {
        return com.chh.autosense.core.security.PlannerInvocationContext.current().deviceList(userId,
                properties.initializationTimeout(), properties.maxPlannerDeviceBytes(),
                deadline -> load(Long.parseLong(userId.trim()), deadline));
    }

    private DeviceListResult load(long id, Instant deadline) {
        var snapshot = service.listMine(new AuthUser(id), deadline);
        var metadata = new LinkedHashMap<String, DeviceStatusMetadata>();
        var basic = new LinkedHashMap<String, DeviceBasicMetadata>();
        snapshot.statusMetadata().forEach((sn, status) -> metadata.put(sn,
                new DeviceStatusMetadata(status.source(), status.observedAt(), status.errorCode(), status.reason())));
        var devices = snapshot.devices().stream().map(device -> {
            basic.put(device.sn(), new DeviceBasicMetadata(null, "SYSTEM_RECORD", List.of("firmwareVersion")));
            return new DeviceBasicInfo(device.id(), device.name(), device.sn(), device.deviceTypeCode(),
                    device.deviceModelCode(), device.online(), null);
        }).toList();
        log.info("Device list tool completed: count={}, result=OK", devices.size());
        return new DeviceListResult(devices, metadata, basic, snapshot.observedAt(), "OK");
    }

    @Override
    public String getToolName() {
        return "deviceList";
    }

    @Override
    public String getDisplayName() {
        return "设备基础信息";
    }

    @Override
    public String generateToolExecutedResult(JSONObject result) {
        return "设备基础信息已加载。";
    }
}
