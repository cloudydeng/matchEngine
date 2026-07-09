package com.matching.core.engine;

import com.matching.disruptor.MarketDataPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
public class MatchingEngineManager {

    private final MarketDataPublisher publisher;
    private final ConcurrentHashMap<String, MatchingEngine> engines = new ConcurrentHashMap<>();

    public MatchingEngine getEngine(String symbol) {
        return engines.computeIfAbsent(symbol, s -> {
            try {
                return new MatchingEngine(s, publisher);
            } catch (Exception e) {  // 捕获所有异常
                throw new RuntimeException("Failed to create engine for " + s, e);
            }
        });
    }

    // 上新交易对时调用

    public MatchingEngine createEngine(String symbol) {
        return engines.computeIfAbsent(symbol, s -> {
            try {
                return new MatchingEngine(s, publisher);
            } catch (Exception e) {  // 捕获所有异常
                throw new RuntimeException("Failed to create engine for " + s, e);
            }
        });
    }


    // 下架交易对时调用
    public void removeEngine(String symbol) {
        engines.remove(symbol);
    }
}
