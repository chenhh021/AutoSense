package com.chh.autosense.core.aftersales;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 固定 mock 售后网点(R14,2026-08-22 澄清):项目内固定数据,按城市/区域键路由,
 * 确定可测;后续接入地图搜索工具时仅需替换实现 Bean(mock-enabled=false)。
 * 位置不含已知城市键时返回空列表(触发官方客服兜底,FR-011)。
 */
@Component
@ConditionalOnProperty(name = "autosense.aftersales.mock-enabled", havingValue = "true",
        matchIfMissing = true)
public class MockAfterSalesClient implements AfterSalesClient {

    private static final List<Map.Entry<String, List<AfterSalesLocation>>> CITY_FIXTURES = List.of(
            Map.entry("杭州", List.of(
                    new AfterSalesLocation("XX 售后服务中心(杭州西湖店)",
                            "杭州市西湖区文三路 100 号", "0571-88000001", 2300L),
                    new AfterSalesLocation("XX 售后服务中心(杭州滨江店)",
                            "杭州市滨江区江南大道 200 号", "0571-88000002", 5800L))),
            Map.entry("上海", List.of(
                    new AfterSalesLocation("XX 售后服务中心(上海徐汇店)",
                            "上海市徐汇区漕溪路 300 号", "021-60000001", 1800L))),
            Map.entry("北京", List.of(
                    new AfterSalesLocation("XX 售后服务中心(北京中关村店)",
                            "北京市海淀区中关村大街 50 号", "010-60000001", 3200L))));

    @Override
    public List<AfterSalesLocation> searchNearby(String locationText, int radiusMeters) {
        if (locationText == null || locationText.isBlank()) {
            return List.of();
        }
        return CITY_FIXTURES.stream()
                .filter(e -> locationText.contains(e.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(List.of());
    }
}
