package com.chh.autosense.service;
import com.chh.autosense.core.security.AuthUser;
import com.chh.autosense.domain.dto.DeviceListSnapshot;
import com.chh.autosense.domain.vo.DeviceView;
import java.time.Instant;
import java.util.List;
public interface DeviceListService {
    DeviceListSnapshot listMine(AuthUser user, Instant deadline);
    List<DeviceView> localMine(AuthUser user);
    DeviceView localBySn(AuthUser user, String sn);
    DeviceListSnapshot.Status onlineStatus(AuthUser user, String sn, Instant deadline);
}
