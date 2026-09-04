package com.chh.autosense.aftersales;

import com.chh.autosense.config.AfterSalesProperties;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 售后引导(FR-011):查询附近网点;位置不可用或查询失败时回退官方客服兜底。
 */
@Service
public class AfterSalesGuideService {

    private final AfterSalesClient client;
    private final AfterSalesProperties properties;

    public AfterSalesGuideService(AfterSalesClient client, AfterSalesProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    public List<AfterSalesLocation> searchNearby(String locationText) {
        return client.searchNearby(locationText, properties.defaultRadiusMeters());
    }

    public AfterSalesLocation officialHotline() {
        return new AfterSalesLocation(
                properties.officialHotlineName(),
                "官方客服热线",
                properties.officialHotlinePhone(),
                null);
    }
}
