package com.matching.core.engine;

import lombok.SneakyThrows;

import java.util.concurrent.ConcurrentHashMap;


public  class MatchingEngineManager {

    private static final ConcurrentHashMap<String, MatchingEngine> engines = new ConcurrentHashMap<>();


    public static MatchingEngine getEngine(String symbol) {
        return engines.computeIfAbsent(symbol, s -> {
            try {
                return new MatchingEngine(s);
            } catch (Exception e) {  // 捕获所有异常
                throw new RuntimeException("Failed to create engine for " + s, e);
            }
        });
    }

    // 上新交易对时调用

    public static MatchingEngine createEngine(String symbol) {
        return engines.computeIfAbsent(symbol, s -> {
            try {
                return new MatchingEngine(s);
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
