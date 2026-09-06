package com.chh.autosense.unit;

import com.chh.autosense.core.aftersales.AfterSalesLocation;
import com.chh.autosense.core.aftersales.HttpAfterSalesClient;
import com.chh.autosense.core.aftersales.MockAfterSalesClient;
import com.chh.autosense.config.AfterSalesProperties;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * T031:售后查询客户端三路径(正常 / 空结果 / 超时回退)+ mock 桩行为。
 */
class AfterSalesClientTest {

    private WireMockServer wireMock;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(0);
        wireMock.start();
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    private HttpAfterSalesClient httpClient(int timeoutSeconds) {
        AfterSalesProperties props = new AfterSalesProperties(
                wireMock.baseUrl(), "test-key", 10000, timeoutSeconds,
                false, "品牌官方客服", "400-000-0000");
        return new HttpAfterSalesClient(props, RestClient.builder());
    }

    @Test
    void 正常返回网点列表() {
        wireMock.stubFor(get(urlPathEqualTo("/pois/nearby")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("[{\"name\":\"XX售后\",\"address\":\"示例路1号\",\"phone\":\"400\",\"distanceMeters\":800}]")));

        List<AfterSalesLocation> result = httpClient(5).searchNearby("杭州西湖区", 10000);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("XX售后");
        assertThat(result.get(0).distanceMeters()).isEqualTo(800L);
    }

    @Test
    void 空结果返回空列表() {
        wireMock.stubFor(get(urlPathEqualTo("/pois/nearby")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("[]")));

        assertThat(httpClient(5).searchNearby("无人区", 10000)).isEmpty();
    }

    @Test
    void 超时或异常回退为空列表_由上层兜底官方客服() {
        wireMock.stubFor(get(urlPathEqualTo("/pois/nearby")).willReturn(aResponse()
                .withFixedDelay(3000)));

        assertThat(httpClient(1).searchNearby("杭州", 10000)).isEmpty();
    }

    @Test
    void mock桩_固定城市数据_未知城市返回空列表() {
        MockAfterSalesClient mock = new MockAfterSalesClient();
        assertThat(mock.searchNearby("", 10000)).isEmpty();
        assertThat(mock.searchNearby("无人区", 10000)).isEmpty();
        assertThat(mock.searchNearby("杭州", 10000)).hasSize(2);
        assertThat(mock.searchNearby("上海", 10000)).hasSize(1);
        assertThat(mock.searchNearby("北京", 10000)).hasSize(1);
    }
}
