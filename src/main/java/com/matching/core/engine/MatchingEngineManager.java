package com.matching.core.engine;

import com.matching.disruptor.MarketDataPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
public class MatchingEngineManager {

    private static final ConcurrentHashMap<String, MatchingEngine> engines = new ConcurrentHashMap<>();

    private static ApplicationContext context;

    @Autowired
    public void setApplicationContext(ApplicationContext ctx) {
        context = ctx;
    }


    public static MatchingEngine getEngine(String symbol) {
        return engines.computeIfAbsent(symbol, s -> {
            try {
                MarketDataPublisher publisher = context.getBean(MarketDataPublisher.class);
                return new MatchingEngine(s, publisher);
            } catch (Exception e) {  // 捕获所有异常
                throw new RuntimeException("Failed to create engine for " + s, e);
            }
        });
    }

    // 上新交易对时调用

    public static MatchingEngine createEngine(String symbol) {
        return engines.computeIfAbsent(symbol, s -> {
            try {
                MarketDataPublisher publisher = context.getBean(MarketDataPublisher.class);
                return new MatchingEngine(s, publisher);
            } catch (Exception e) {  // 捕获所有异常
                throw new RuntimeException("Failed to create engine for " + s, e);
            }
        });
    }


    // 下架交易对时调用
    public static void removeEngine(String symbol) {
        MatchingEngine engine = engines.remove(symbol);
        if (engine != null) {
            engines.remove(symbol);
        }
    }
}
