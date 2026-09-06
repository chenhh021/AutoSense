package com.chh.autosense.core.aftersales;

import com.chh.autosense.config.AfterSalesProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

/**
 * 外部售后查询服务 HTTP 客户端(research R8/R14):RestClient,配置经
 * AfterSalesProperties 注入;查询失败返回空列表(由调用方回退官方客服)。
 * 仅当 mock-enabled=false(接入真实地图搜索工具)时装配。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "autosense.aftersales.mock-enabled", havingValue = "false")
public class HttpAfterSalesClient implements AfterSalesClient {

    private final RestClient restClient;

    public HttpAfterSalesClient(AfterSalesProperties props, RestClient.Builder builder) {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(props.timeoutSeconds()));
        factory.setReadTimeout(Duration.ofSeconds(props.timeoutSeconds()));
        this.restClient = builder
                .baseUrl(props.baseUrl())
                .defaultHeader("X-Api-Key", props.apiKey() == null ? "" : props.apiKey())
                .requestFactory(factory)
                .build();
    }

    @Override
    public List<AfterSalesLocation> searchNearby(String locationText, int radiusMeters) {
        try {
            List<AfterSalesLocation> result = restClient.get()
                    .uri(uri -> uri.path("/pois/nearby")
                            .queryParam("location", locationText)
                            .queryParam("radius", radiusMeters)
                            .build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            return result == null ? List.of() : result;
        } catch (Exception e) {
            log.warn("售后网点查询失败,回退官方客服: {}", e.getMessage());
            return List.of();
        }
    }
}
