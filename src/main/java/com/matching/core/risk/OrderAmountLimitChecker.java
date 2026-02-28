package com.matching.core.risk;

import com.matching.core.domain.Order;
import com.matching.core.domain.OrderType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 订单数量限制检查器
 * 限制用户的挂单总数和单笔金额
 */
@Slf4j
@Component
public class OrderAmountLimitChecker implements RiskChecker {

    @Value("${risk.max-open-orders:200}")
    private int maxOpenOrdersPerSymbol;  // 每个交易对最多挂单数

    @Value("${risk.max-order-amount:100000}")
    private BigDecimal maxOrderAmount;  // 单笔最大金额（USDT）

    @Value("${risk.daily-limit:10000}")
    private BigDecimal dailyLimit;  // 日交易量限制（USDT）

    // 按用户和交易对统计挂单数
    // Key: symbol:userId
    private final ConcurrentHashMap<String, AtomicInteger> openOrderCounts = new ConcurrentHashMap<>();

    // 按用户统计日交易量
    // Key: userId, Value: 日交易量
    private final ConcurrentHashMap<String, DailyVolume> dailyVolumes = new ConcurrentHashMap<>();

    @Override
    public RiskCheckResult checkOrder(Order order) {
        if (order.getUserId() == null) {
            return RiskCheckResult.allowed();
        }

        // 1. 检查挂单数量限制
        String key = order.getSymbol() + ":" + order.getUserId();
        AtomicInteger count = openOrderCounts.computeIfAbsent(key, k -> new AtomicInteger(0));

        if (count.get() >= maxOpenOrdersPerSymbol) {
            log.warn("Open order limit exceeded: userId={}, symbol={}, count={}",
                    order.getUserId(), order.getSymbol(), count.get());
            return RiskCheckResult.rejected("挂单数量超限", RiskErrorCode.TOO_MANY_OPEN_ORDERS);
        }

        // 2. 检查单笔金额限制（市价单价格未知，跳过该项）
        if (order.getType() == OrderType.LIMIT) {
            if (order.getPrice() == null) {
                return RiskCheckResult.rejected("限价单价格不能为空", RiskErrorCode.INVALID_ORDER_TYPE);
            }
            BigDecimal orderAmount = order.getPrice().multiply(order.getQuantity());
            if (orderAmount.compareTo(maxOrderAmount) > 0) {
                log.warn("Order amount too large: userId={}, amount={}, limit={}",
                        order.getUserId(), orderAmount, maxOrderAmount);
                return RiskCheckResult.rejected("单笔金额超限", RiskErrorCode.ORDER_AMOUNT_TOO_LARGE);
            }
        }

        // 3. 检查日交易量限制（简化版，实际需要统计）
        DailyVolume dailyVolume = dailyVolumes.computeIfAbsent(
                order.getUserId(),
                k -> new DailyVolume());

        if (dailyVolume.isExceeded(dailyLimit)) {
            log.warn("Daily limit exceeded: userId={}, volume={}, limit={}",
                    order.getUserId(), dailyVolume.getVolume(), dailyLimit);
            return RiskCheckResult.rejected("日交易量超限", RiskErrorCode.DAILY_LIMIT_EXCEEDED);
        }

        return RiskCheckResult.allowed();
    }

    @Override
    public RiskCheckResult checkCancel(String orderId, String userId) {
        if (userId == null) {
            return RiskCheckResult.allowed();
        }

        String symbol = extractSymbol(orderId);
        String key = symbol + ":" + userId;
        AtomicInteger count = openOrderCounts.get(key);

        if (count != null && count.get() > 0) {
            count.decrementAndGet();
        }

        return RiskCheckResult.allowed();
    }

    /**
     * 增加挂单计数（订单提交成功后调用）
     */
    public void incrementOpenOrder(String symbol, String userId) {
        if (userId == null) return;
        String key = symbol + ":" + userId;
        openOrderCounts.computeIfAbsent(key, k -> new AtomicInteger(0)).incrementAndGet();
    }

    /**
     * 增加日交易量（成交后调用）
     */
    public void addDailyVolume(String userId, BigDecimal amount) {
        if (userId == null) return;
        DailyVolume dailyVolume = dailyVolumes.computeIfAbsent(userId, k -> new DailyVolume());
        dailyVolume.addVolume(amount);
    }

    /**
     * 重置日交易量（每天零点调用）
     */
    public void resetDailyVolumes() {
        dailyVolumes.clear();
    }

    private String extractSymbol(String orderId) {
        int idx = orderId.indexOf('_');
        return idx > 0 ? orderId.substring(0, idx) : orderId;
    }

    @Override
    public String getName() {
        return "OrderAmountLimitChecker";
    }

    /**
     * 日交易量记录
     */
    private static class DailyVolume {
        private volatile BigDecimal volume = BigDecimal.ZERO;
        private volatile long date = System.currentTimeMillis() / 86400000; // 当前日期

        synchronized void addVolume(BigDecimal amount) {
            // 检查日期是否已过，自动重置
            long currentDate = System.currentTimeMillis() / 86400000;
            if (date != currentDate) {
                volume = BigDecimal.ZERO;
                date = currentDate;
            }
            volume = volume.add(amount);
        }

        synchronized BigDecimal getVolume() {
            return volume;
        }

        boolean isExceeded(BigDecimal limit) {
            return getVolume().compareTo(limit) > 0;
        }
    }
}
