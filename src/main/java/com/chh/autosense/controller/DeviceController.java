package com.chh.autosense.controller;

import com.chh.autosense.common.BaseResponse;
import com.chh.autosense.utils.ResultUtils;
import com.chh.autosense.domain.vo.DeviceView;
import com.chh.autosense.domain.dto.RegisterDeviceRequest;
import com.chh.autosense.service.device.DeviceAdapterRegistryService;
import com.chh.autosense.service.device.DeviceRegistryService;
import com.chh.autosense.domain.entity.Device;
import com.chh.autosense.core.security.AuthUser;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
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
 * 列表日志按整体汇总(count/onlineCount),不逐项打印,不含 SN/名称。
 */
@RestController
@Slf4j
@RequestMapping("/api/v1/devices")
public class DeviceController {

    private final DeviceRegistryService registryService;
    private final DeviceAdapterRegistryService adapterRegistry;

    public DeviceController(DeviceRegistryService registryService,
                            DeviceAdapterRegistryService adapterRegistry) {
        this.registryService = registryService;
        this.adapterRegistry = adapterRegistry;
    }

    /**
     * 登记当前用户的设备（按 SN 发现并绑定）
     *
     * @param user    Spring Security 注入的当前登录用户
     * @param request 设备 SN 与名称
     * @return 包装响应,业务数据为已绑定设备的视图；绑定即代表刚按 SN 发现设备在运行，直接置为在线
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BaseResponse<DeviceView> register(@AuthenticationPrincipal AuthUser user,
                                             @Valid @RequestBody RegisterDeviceRequest request) {
        Device d = registryService.register(user.userId(), request.sn(), request.name());
        // 绑定即代表刚按 SN 发现设备在运行，直接置为在线，不再重复探测
        return ResultUtils.success(toView(d, true));
    }

    /**
     * 获取当前用户已绑定的设备列表
     *
     * @param user Spring Security 注入的当前登录用户
     * @return 设备列表（含实时在线状态，键 devices）；列表日志按整体汇总，不逐项打印
     */
    @GetMapping
    public Map<String, List<DeviceView>> listMine(@AuthenticationPrincipal AuthUser user) {
        List<DeviceView> devices = registryService.listMine(user.userId()).stream()
                .map(d -> toView(d, registryService.isOnline(d)))
                .toList();
        long onlineCount = devices.stream().filter(DeviceView::online).count();
        log.info("Device operation completed: operation=list, result=OK, count={}, onlineCount={}",
                devices.size(), onlineCount);
        return Map.of("devices", devices);
    }

    /**
     * 将设备实体转为对外视图
     *
     * @param d      设备实体
     * @param online 当前在线状态
     * @return 脱敏的设备视图
     */
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
