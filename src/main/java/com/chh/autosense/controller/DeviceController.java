package com.chh.autosense.controller;

import com.chh.autosense.api.dto.DeviceView;
import com.chh.autosense.api.dto.RegisterDeviceRequest;
import com.chh.autosense.device.DeviceAdapterRegistry;
import com.chh.autosense.device.DeviceRegistryService;
import com.chh.autosense.domain.model.Device;
import com.chh.autosense.security.AuthUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 设备登记与查询 API(FR-020,contracts §6/§7)。
 */
@RestController
@RequestMapping("/api/v1/devices")
public class DeviceController {

    private final DeviceRegistryService registryService;
    private final DeviceAdapterRegistry adapterRegistry;

    public DeviceController(DeviceRegistryService registryService,
                            DeviceAdapterRegistry adapterRegistry) {
        this.registryService = registryService;
        this.adapterRegistry = adapterRegistry;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DeviceView register(@AuthenticationPrincipal AuthUser user,
                               @Valid @RequestBody RegisterDeviceRequest request) {
        Device d = registryService.register(user.userId(), request.sn(), request.name());
        // 绑定即代表刚按 SN 发现设备在运行，直接置为在线，不再重复探测
        return toView(d, true);
    }

    @GetMapping
    public Map<String, List<DeviceView>> listMine(@AuthenticationPrincipal AuthUser user) {
        return Map.of("devices", registryService.listMine(user.userId()).stream()
                .map(d -> toView(d, registryService.isOnline(d)))
                .toList());
    }

    private DeviceView toView(Device d, boolean online) {
        return new DeviceView(
                d.getId(),
                d.getName(),
                d.getSimulatorName(),
                d.getSn(),
                d.getDeviceTypeCode(),
                d.getDeviceTypeId(),
                d.getDeviceModelCode(),
                d.getDeviceModelId(),
                adapterRegistry.isSupported(d),
                online);
    }
}
