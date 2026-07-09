package com.matching.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
public class MarketDataWebSocketHandler extends TextWebSocketHandler {
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    private final ObjectMapper objectMapper;

    public MarketDataWebSocketHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
    }

    public void broadcast(String symbol,
                          NavigableMap<BigDecimal, BigDecimal> bids,
                          NavigableMap<BigDecimal, BigDecimal> asks,
                          int levels) {
        String json;
        try {
            json = buildDepthJson(symbol, bids, asks, levels);
        } catch (JsonProcessingException e) {
            log.warn("WebSocket 深度序列化失败", e);
            return;
        }

        TextMessage message = new TextMessage(json);
        log.debug("websocket push message {}", json);
        sessions.forEach(session -> {
            try {
                if (session.isOpen()) {
                    synchronized (session) {
                        session.sendMessage(message);
                    }
                } else {
                    sessions.remove(session);
                }
            } catch (IOException e) {
                sessions.remove(session);
                log.warn("WebSocket 推送失败", e);
            }
        });
    }

    String buildDepthJson(String symbol,
                          NavigableMap<BigDecimal, BigDecimal> bids,
                          NavigableMap<BigDecimal, BigDecimal> asks,
                          int levels) throws JsonProcessingException {
        DepthPayload payload = new DepthPayload(
                symbol,
                System.currentTimeMillis(),
                toLevelArrays(bids, levels),
                toLevelArrays(asks, levels)
        );
        return objectMapper.writeValueAsString(payload);
    }

    private List<List<String>> toLevelArrays(NavigableMap<BigDecimal, BigDecimal> levelsMap, int levels) {
        List<List<String>> result = new ArrayList<>();
        int count = 0;
        for (var e : levelsMap.entrySet()) {
            if (count >= levels) {
                break;
            }
            if (e.getValue().signum() <= 0) {
                continue;
            }
            result.add(List.of(
                    e.getKey().stripTrailingZeros().toPlainString(),
                    e.getValue().stripTrailingZeros().toPlainString()
            ));
            count++;
        }
        return result;
    }

    private record DepthPayload(String symbol, long ts, List<List<String>> bids, List<List<String>> asks) {
    }
}
