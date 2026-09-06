package com.chh.autosense.core.aftersales;

/**
 * 售后网点(data-model.md §9,非持久化,外部查询结果)。
 */
public record AfterSalesLocation(
        String name,
        String address,
        String phone,
        Long distanceMeters
) {
}
