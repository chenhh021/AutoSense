package com.chh.autosense.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查 API，供部署探活与负载均衡探测使用。
 */
@RestController
@RequestMapping("/health")
public class HealthController {

    /**
     * 健康检查
     *
     * @return 固定返回 "ok"
     */
    @GetMapping
    public String healthCheck() {
        return "ok";
    }
}

