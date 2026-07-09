package com.matching.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MarketDataWebSocketHandlerTest {

    @Test
    void buildsDepthJsonWithArrayLevels() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        MarketDataWebSocketHandler handler = new MarketDataWebSocketHandler(mapper);
        TreeMap<BigDecimal, BigDecimal> bids = new TreeMap<>(Comparator.reverseOrder());
        TreeMap<BigDecimal, BigDecimal> asks = new TreeMap<>();
        bids.put(new BigDecimal("100.00"), new BigDecimal("2.500"));
        asks.put(new BigDecimal("101.00"), new BigDecimal("1.250"));

        String json = handler.buildDepthJson("BTCUSDT", bids, asks, 20);
        JsonNode root = mapper.readTree(json);

        assertEquals("BTCUSDT", root.get("symbol").asText());
        assertEquals("100", root.get("bids").get(0).get(0).asText());
        assertEquals("2.5", root.get("bids").get(0).get(1).asText());
        assertEquals("101", root.get("asks").get(0).get(0).asText());
        assertEquals("1.25", root.get("asks").get(0).get(1).asText());
    }
}
