package com.matching.opinion.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MarketVolumeMonitorService {

    private static final Logger log = LoggerFactory.getLogger(MarketVolumeMonitorService.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String apiBase;
    private final String apiKey;


    private final Map<String, String> lastVolumeMap = new ConcurrentHashMap<>();

    public MarketVolumeMonitorService(
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            @Value("${opinion.api.base:https://proxy.opinion.trade:8443/openapi}") String apiBase,
            @Value("${opinion.api.key:M6IL72onrUqGcwKhkHaMRknpVs9OJPvx}") String apiKey) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.apiBase = apiBase;
        this.apiKey = apiKey;
    }


    @Scheduled(fixedRate = 60000)
    public void pollAndCompareVolumes() {
        try {
            String url = apiBase + "/market?status=activated&sortBy=5&limit=20";

            HttpHeaders headers = new HttpHeaders();
            headers.set("apikey", apiKey);
            HttpEntity<?> entity = new HttpEntity<>(headers);

            String responseBody = restTemplate.exchange(url, HttpMethod.GET, entity, String.class).getBody();

            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode list = root.path("result").path("list");

            for (JsonNode market : list) {
                log.info("market detail {}",market.toString());
                String marketId = market.path("marketId").asText();
                String currentVolumeStr = market.path("volume").asText("0");

                String lastVolumeStr = lastVolumeMap.get(marketId);

                if (lastVolumeStr != null) {
                    try {
                        BigDecimal current = new BigDecimal(currentVolumeStr);
                        BigDecimal previous = new BigDecimal(lastVolumeStr);
                        BigDecimal diff = current.subtract(previous);

                        log.info("市场 [{}] 最近1分钟交易量变化: {} (当前累计: {}, 上次: {})",
                                marketId, diff.setScale(8, RoundingMode.HALF_UP).stripTrailingZeros(), currentVolumeStr, lastVolumeStr);

                        // 这里可以加业务逻辑，例如：
                        // if (diff.compareTo(BigDecimal.valueOf(5000)) > 0) { 通知/报警... }
                    } catch (NumberFormatException e) {
                        log.warn("volume 格式异常 marketId={}, current={}, last={}",
                                marketId, currentVolumeStr, lastVolumeStr);
                    }
                } else {
                    log.info("市场 [{}] 首次获取 volume: {}", marketId, currentVolumeStr);
                }

                // 更新内存快照
                lastVolumeMap.put(marketId, currentVolumeStr);
            }

        } catch (Exception e) {
            log.error("轮询市场 volume 失败", e);
        }
    }


}
