package com.matching.api;

import jakarta.websocket.OnClose;
import jakarta.websocket.OnOpen;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
public class MarketDataWebSocketHandler {
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();

    @OnOpen
    public void onOpen(WebSocketSession session) { sessions.add(session); }

    @OnClose
    public void onClose(WebSocketSession session) { sessions.remove(session); }

    public void broadcast(String symbol,
                          TreeMap<BigDecimal, BigDecimal> bids,   // 降序
                          TreeMap<BigDecimal, BigDecimal> asks,   // 升序
                          int levels) {
        String json = buildDepthJson(symbol, bids, asks, levels);
        TextMessage message = new TextMessage(json);

        sessions.parallelStream().forEach(session -> {
            try {
                if (session.isOpen()) {
                    session.sendMessage(message);
                }
            } catch (IOException e) {
                log.warn("WebSocket 推送失败", e);
            }
        });
    }

    private String buildDepthJson(String symbol,
                                  TreeMap<BigDecimal, BigDecimal> bids,
                                  TreeMap<BigDecimal, BigDecimal> asks,
                                  int levels) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("{\"symbol\":\"").append(symbol)
                .append("\",\"ts\":").append(System.currentTimeMillis())
                .append(",\"bids\":[");

        int count = 0;
        for (var e : bids.entrySet()) {
            if (count++ >= levels) break;
            if (e.getValue().signum() <= 0) continue; // qty <= 0 不推
            if (count > 1) sb.append(',');
            sb.append("[\"")
                    .append(e.getKey().stripTrailingZeros().toPlainString())
                    .append("\",\"")
                    .append(e.getValue().stripTrailingZeros().toPlainString())
                    .append("\"]");
        }

        sb.append("],\"asks\":[");
        count = 0;
        for (var e : asks.entrySet()) {
            if (count++ >= levels) break;
            if (e.getValue().signum() <= 0) continue;
            if (count > 1) sb.append(',');
            sb.append("[\"")
                    .append(e.getKey().stripTrailingZeros().toPlainString())
                    .append("\",\"")
                    .append(e.getValue().stripTrailingZeros().toPlainString())
                    .append("\"]");
        }

        sb.append("]}");
        return sb.toString();
    }
}
