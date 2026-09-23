package com.chh.autosense.service.impl;

import com.chh.autosense.config.DeviceQueryProperties;
import com.chh.autosense.core.device.client.DeviceServiceClient;
import com.chh.autosense.core.security.*;
import com.chh.autosense.domain.dto.DeviceListSnapshot;
import com.chh.autosense.domain.entity.Device;
import com.chh.autosense.domain.vo.DeviceView;
import com.chh.autosense.exception.*;
import com.chh.autosense.mapper.*;
import com.chh.autosense.service.DeviceListService;
import com.chh.autosense.service.device.*;
import com.chh.autosense.utils.LogContextUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceListServiceImpl implements DeviceListService {
    private final DeviceRegistryService registry;
    private final DeviceAdapterRegistryService adapters;
    private final DeviceMapper devices;
    private final UserMapper users;
    private final DeviceServiceClient client;
    private final DeviceOwnershipChecker ownership;
    private final DeviceQueryProperties properties;

    @Override public DeviceListSnapshot listMine(AuthUser user, Instant deadline) {
        long started = System.nanoTime();
        // Local failures must propagate. Only failures of the online lookup degrade to offline.
        List<DeviceView> local = localMine(user);
        var status = new LinkedHashMap<String, DeviceListSnapshot.Status>();
        var pending = new LinkedHashMap<String, Future<DeviceListSnapshot.Status>>();
        var executor = Executors.newFixedThreadPool(properties.onlineLookupParallelism(), Thread.ofVirtual().factory());
        Map<String, String> logging = MDC.getCopyOfContextMap();
        try {
            for (var device : local) {
                if (!Instant.now().isBefore(deadline)) break;
                pending.put(device.sn(), executor.submit(() -> {
                    if (logging != null) MDC.setContextMap(logging);
                    try {
                        if (!Instant.now().isBefore(deadline)) return offline("REQUEST_TIMEOUT");
                        return lookup(device.sn());
                    } finally { MDC.clear(); }
                }));
            }
            for (var device : local) {
                var future = pending.get(device.sn());
                DeviceListSnapshot.Status value;
                try {
                    long remaining = Duration.between(Instant.now(), deadline).toNanos();
                    value = future == null ? offline("REQUEST_TIMEOUT") : future.isDone() ? future.get()
                            : remaining > 0 ? future.get(remaining, TimeUnit.NANOSECONDS) : offline("REQUEST_TIMEOUT");
                    if (value.observedAt().isAfter(deadline)) value = offline("REQUEST_TIMEOUT");
                } catch (TimeoutException | ExecutionException e) { value = offline("REQUEST_TIMEOUT"); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); throw new ApiException(ErrorCode.REQUEST_TIMEOUT, "Device initialization interrupted");
                }
                status.put(device.sn(), value);
            }
        } finally {
            pending.values().forEach(future -> future.cancel(true));
            // Do not wait for a provider that ignores interruption. Workers only return values;
            // they never hold or mutate the published snapshot.
            executor.shutdownNow();
        }
        var result = local.stream().map(device -> withOnline(device, status.get(device.sn()))).toList();
        log.info("Device list completed: count={}, degradedCount={}, elapsedMs={}", result.size(),
                status.values().stream().filter(value -> value.source().equals("DEFAULT_OFFLINE")).count(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        return new DeviceListSnapshot(result, status, Instant.now());
    }

    @Override public List<DeviceView> localMine(AuthUser user) {
        requireUser(user);
        return registry.listMine(user.userId()).stream().map(device -> {
            ownership.check(device, user);
            return view(device, false);
        }).toList();
    }

    @Override public DeviceView localBySn(AuthUser user, String sn) {
        requireUser(user);
        var device = devices.selectOneBySn(sn); ownership.check(device, user);
        return view(device, false);
    }

    @Override public DeviceListSnapshot.Status onlineStatus(AuthUser user, String sn, Instant deadline) {
        localBySn(user, sn);
        if (!Instant.now().isBefore(deadline)) return offline("REQUEST_TIMEOUT");
        return lookup(sn);
    }

    private DeviceListSnapshot.Status lookup(String sn) {
        try {
            boolean online = client.isDeviceOnline(sn);
            return new DeviceListSnapshot.Status("EXTERNAL", Instant.now(), online ? "OK" : "DEVICE_OFFLINE", null);
        } catch (RuntimeException e) { return offline("DEVICE_SERVICE_UNAVAILABLE"); }
    }

    private DeviceListSnapshot.Status offline(String code) {
        return new DeviceListSnapshot.Status("DEFAULT_OFFLINE", Instant.now(), code, "在线状态未获取，按离线处理");
    }

    private void requireUser(AuthUser user) {
        if (user == null || user.userId() == null || user.userId() <= 0) throw new ApiException(ErrorCode.UNAUTHORIZED, "Invalid user");
        var account = users.selectByIdIncludingDeleted(user.userId());
        if (account == null || account.getIsDelete() != null && account.getIsDelete() != 0)
            throw new ApiException(ErrorCode.UNAUTHORIZED, "Account unavailable");
    }

    private DeviceView view(Device device, boolean online) {
        return new DeviceView(device.getId(), device.getName(), device.getSimulatorName(), device.getSn(),
                device.getDeviceTypeCode(), device.getDeviceTypeId(), device.getDeviceModelCode(), device.getDeviceModelId(),
                adapters.isSupported(device), online);
    }

    private DeviceView withOnline(DeviceView view, DeviceListSnapshot.Status status) {
        return new DeviceView(view.id(), view.name(), view.simulatorName(), view.sn(), view.deviceTypeCode(), view.deviceTypeId(),
                view.deviceModelCode(), view.deviceModelId(), view.supported(), status.source().equals("EXTERNAL") && status.errorCode().equals("OK"));
    }
}
