package com.matching.core.risk;

import com.matching.core.domain.Order;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 用户频率限制检查器
 * 基于滑动窗口算法
 */
@Slf4j
@Component
public class UserRateLimitChecker implements RiskChecker {

    @Value("${risk.order.rate-limit:100}")
    private int orderRateLimit;  // 每秒最多下单数

    @Value("${risk.cancel.rate-limit:50}")
    private int cancelRateLimit;  // 每秒最多撤单数

    @Value("${risk.api.rate-limit:200}")
    private int apiRateLimit;    // 每秒最多API调用数

    // 按用户记录滑动窗口
    // Key: symbol:userId, Value: 滑动窗口数据
    private final ConcurrentHashMap<String, RateWindow> orderWindows = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RateWindow> cancelWindows = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RateWindow> apiWindows = new ConcurrentHashMap<>();

    @Override
    public RiskCheckResult checkOrder(Order order) {
        if (order.getUserId() == null) {
            return RiskCheckResult.allowed();
        }

        String key = order.getSymbol() + ":" + order.getUserId();
        RateWindow window = orderWindows.computeIfAbsent(key, k -> new RateWindow());

        if (!window.tryAcquire(orderRateLimit)) {
            log.warn("Order rate limit exceeded: userId={}, symbol={}, limit={}",
                    order.getUserId(), order.getSymbol(), orderRateLimit);
            return RiskCheckResult.rejected("下单频率超限", RiskErrorCode.ORDER_RATE_LIMIT);
        }

        return RiskCheckResult.allowed();
    }

    @Override
    public RiskCheckResult checkCancel(String orderId, String userId) {
        if (userId == null) {
            return RiskCheckResult.allowed();
        }

        // 从 orderId 提取 symbol（简化实现）
        String symbol = extractSymbol(orderId);
        String key = symbol + ":" + userId;

        RateWindow window = cancelWindows.computeIfAbsent(key, k -> new RateWindow());

        if (!window.tryAcquire(cancelRateLimit)) {
            log.warn("Cancel rate limit exceeded: userId={}, symbol={}, limit={}",
                    userId, symbol, cancelRateLimit);
            return RiskCheckResult.rejected("撤单频率超限", RiskErrorCode.CANCEL_RATE_LIMIT);
        }

        return RiskCheckResult.allowed();
    }

    /**
     * 检查 API 调用频率（按 IP 或用户）
     */
    public boolean checkApiRate(String identifier) {
        RateWindow window = apiWindows.computeIfAbsent(identifier, k -> new RateWindow());
        return window.tryAcquire(apiRateLimit);
    }

    /**
     * 清理过期窗口数据（定时任务调用）
     */
    public void cleanup() {
        long now = System.currentTimeMillis();
        long expiredTime = now - Duration.ofMinutes(5).toMillis();

        orderWindows.entrySet().removeIf(e -> e.getValue().lastUpdateTime.get() < expiredTime);
        cancelWindows.entrySet().removeIf(e -> e.getValue().lastUpdateTime.get() < expiredTime);
        apiWindows.entrySet().removeIf(e -> e.getValue().lastUpdateTime.get() < expiredTime);
    }

    private String extractSymbol(String orderId) {
        int idx = orderId.indexOf('_');
        return idx > 0 ? orderId.substring(0, idx) : orderId;
    }

    @Override
    public String getName() {
        return "UserRateLimitChecker";
    }

    /**
     * 滑动窗口实现（简化版，每秒重置）
     */
    private static class RateWindow {
        private final AtomicInteger counter = new AtomicInteger(0);
        private final AtomicLong lastUpdateTime = new AtomicLong(System.currentTimeMillis());
        private volatile long windowStart;

        RateWindow() {
            this.windowStart = System.currentTimeMillis();
        }

        synchronized boolean tryAcquire(int limit) {
            long now = System.currentTimeMillis();

            // 每秒重置窗口
            if (now - windowStart >= 1000) {
                counter.set(0);
                windowStart = now;
            }

            if (counter.incrementAndGet() <= limit) {
                lastUpdateTime.set(now);
                return true;
            }
            return false;
        }
    }
}
