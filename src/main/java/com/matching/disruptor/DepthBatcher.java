package com.matching.disruptor;

import com.matching.api.MarketDataWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import com.lmax.disruptor.EventHandler;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 行情防抖合并神器（50ms 合并一次）
 * 作用：把每秒 100 万+ 次盘口变更 → 压缩成每秒 20 次丝滑推送
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DepthBatcher implements EventHandler<MarketDataEvent> {

    private final MarketDataWebSocketHandler wsHandler;

    // symbol → 买盘缓冲区（价格降序）
    private final ConcurrentHashMap<String, TreeMap<BigDecimal, BigDecimal>> bidBuffers = new ConcurrentHashMap<>();

    // symbol → 卖盘缓冲区（价格升序）
    private final ConcurrentHashMap<String, TreeMap<BigDecimal, BigDecimal>> askBuffers = new ConcurrentHashMap<>();

    // 防抖定时器（50ms 合并一次）
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            r -> {
                Thread t = new Thread(r, "depth-batcher-flusher");
                t.setDaemon(true);
                return t;
            }
    );

    // 启动时开启定时推送
    @PostConstruct
    public void init() {
        scheduler.scheduleAtFixedRate(this::flushAll, 50, 50, TimeUnit.MILLISECONDS);
        log.info("DepthBatcher 已启动，50ms 防抖合并开启");
    }

    // 关闭时停止定时器
    @PreDestroy
    public void destroy() {
        scheduler.shutdownNow();
        log.info("DepthBatcher 已关闭");
    }

    @Override
    public void onEvent(MarketDataEvent event, long sequence, boolean endOfBatch) {
        if (event == null || event.symbol == null) {
            return;
        }

        // 买盘更新
        event.bids.forEach(update -> {
            var buffer = bidBuffers.computeIfAbsent(event.symbol, k ->
                    new TreeMap<>(java.util.Comparator.reverseOrder()));
            if (update.quantity().signum() == 0) {
                buffer.remove(update.price());
            } else {
                buffer.put(update.price(), update.quantity());
            }
        });

        // 卖盘更新
        event.asks.forEach(update -> {
            var buffer = askBuffers.computeIfAbsent(event.symbol, k ->
                    new TreeMap<>(java.util.Comparator.naturalOrder()));
            if (update.quantity().signum() == 0) {
                buffer.remove(update.price());
            } else {
                buffer.put(update.price(), update.quantity());
            }
        });

        // endOfBatch 时可以提前推送（可选，降低延迟）
        if (endOfBatch) {
            flushSymbol(event.symbol);
        }
    }

    // 定时批量推送
    private void flushAll() {
        bidBuffers.keySet().forEach(this::flushSymbol);
        askBuffers.keySet().forEach(this::flushSymbol);
    }

    // 推送单个交易对的最新深度
    private void flushSymbol(String symbol) {
        TreeMap<BigDecimal, BigDecimal> bids = bidBuffers.get(symbol);
        TreeMap<BigDecimal, BigDecimal> asks = askBuffers.get(symbol);

        boolean hasBid = bids != null && !bids.isEmpty();
        boolean hasAsk = asks != null && !asks.isEmpty();

        if (!hasBid && !hasAsk) {
            return;
        }

        // 清理空缓冲区
        if (bids != null && bids.isEmpty()) {
            bidBuffers.remove(symbol);
        }
        if (asks != null && asks.isEmpty()) {
            askBuffers.remove(symbol);
        }

        // 调用 WebSocketHandler 的 buildDepthJson 并广播
        wsHandler.broadcast(
                symbol,
                hasBid ? bids : new TreeMap<>(java.util.Comparator.reverseOrder()),
                hasAsk ? asks : new TreeMap<>(),
                20
        );
    }
}
