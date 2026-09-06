package com.chh.autosense.core.device.adapter;

import com.chh.autosense.config.DeviceTypeRegistryProperties;
import com.chh.autosense.core.device.client.DeviceServiceClient;
import org.springframework.stereotype.Component;

@Component
public class SmartBulbAdapter extends AbstractDeviceAdapter {

    public SmartBulbAdapter(DeviceServiceClient client, DeviceTypeRegistryProperties registry) {
        super(client, registry);
    }

    @Override
    public String deviceType() {
        return "smart_bulb";
    }
}
