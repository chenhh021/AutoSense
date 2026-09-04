package com.chh.autosense.aftersales;

import java.util.List;

/**
 * 售后网点查询(research R8,FR-011)。实现:HttpAfterSalesClient(配置 base-url 时)
 * / MockAfterSalesClient(默认)。
 */
public interface AfterSalesClient {

    /**
     * @param locationText 用户提供的文字位置(城市/区县/地址)
     * @return 附近网点;位置为空或查询失败返回空列表(由调用方回退官方客服)
     */
    List<AfterSalesLocation> searchNearby(String locationText, int radiusMeters);
}
