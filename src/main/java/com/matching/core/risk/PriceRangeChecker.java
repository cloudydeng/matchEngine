package com.matching.core.risk;

import com.matching.core.domain.Order;
import com.matching.core.domain.OrderType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 价格范围检查器
 * 验证订单价格是否在合理范围内
 */
@Slf4j
@Component
public class PriceRangeChecker implements RiskChecker {

    @Value("${risk.max-price-deviation:0.5}")
    private double maxPriceDeviation;  // 最大价格偏离度 (0.5 = ±50%)

    @Value("${risk.btcusdt.min-price:1000}")
    private BigDecimal btcMinPrice;

    @Value("${risk.btcusdt.max-price:1000000}")
    private BigDecimal btcMaxPrice;

    @Value("${risk.min-tick-size:0.01}")
    private BigDecimal minTickSize;  // 最小价格变动单位

    @Value("${risk.min-qty:0.001}")
    private BigDecimal minQuantity;  // 最小数量

    // 各交易对的价格范围配置
    private final Map<String, PriceRange> priceRanges = new ConcurrentHashMap<>();

    public PriceRangeChecker() {
        // 初始化 BTCUSDT 价格范围
        priceRanges.put("BTCUSDT", new PriceRange(btcMinPrice, btcMaxPrice));
    }

    @Override
    public RiskCheckResult checkOrder(Order order) {
        // 1. 检查数量有效性
        if (order.getQuantity() == null || order.getQuantity().compareTo(minQuantity) < 0) {
            log.warn("Invalid quantity: {}", order.getQuantity());
            return RiskCheckResult.rejected("数量不能小于最小值", RiskErrorCode.INVALID_QUANTITY);
        }

        // 2. 检查价格精度（必须是 minTickSize 的整数倍）
        if (order.getType() == OrderType.LIMIT && order.getPrice() != null) {
            BigDecimal remainder = order.getPrice().remainder(minTickSize);
            if (remainder.compareTo(BigDecimal.ZERO) != 0) {
                log.warn("Price precision invalid: price={}, tickSize={}",
                        order.getPrice(), minTickSize);
                return RiskCheckResult.rejected("价格精度不符合要求", RiskErrorCode.PRICE_PRECISION_INVALID);
            }
        }

        // 3. 检查价格是否在允许范围内
        if (order.getType() == OrderType.LIMIT && order.getPrice() != null) {
            PriceRange range = priceRanges.get(order.getSymbol());
            if (range != null) {
                if (order.getPrice().compareTo(range.min) < 0 ||
                    order.getPrice().compareTo(range.max) > 0) {
                    log.warn("Price out of range: symbol={}, price={}, range={}-{}",
                            order.getSymbol(), order.getPrice(), range.min, range.max);
                    return RiskCheckResult.rejected("价格超出允许范围", RiskErrorCode.PRICE_OUT_OF_RANGE);
                }
            }
        }

        // 4. 检查价格是否偏离市场价（如果有订单簿数据）
        // 这里简化处理，实际需要从订单簿获取最新价
        // if (isPriceDeviateTooMuch(order)) {
        //     return RiskCheckResult.rejected("价格偏离市场价过大", RiskErrorCode.PRICE_OUT_OF_RANGE);
        // }
        return RiskCheckResult.allowed();
    }

    /**
     * 检查价格是否偏离最新成交价过大
     * （简化版，实际需要传入订单簿或最新价）
     */
    @SuppressWarnings("unused")
    private boolean isPriceDeviateTooMuch(Order order) {
        // TODO: 从订单簿获取最新成交价
        BigDecimal lastPrice = getLastPrice(order.getSymbol());
        if (lastPrice == null || lastPrice.compareTo(BigDecimal.ZERO) == 0) {
            return false;  // 无法判断，通过
        }

        BigDecimal deviation = order.getPrice().subtract(lastPrice)
                .abs()
                .divide(lastPrice, 4, RoundingMode.HALF_UP);

        return deviation.compareTo(BigDecimal.valueOf(maxPriceDeviation)) > 0;
    }

    private BigDecimal getLastPrice(String symbol) {
        // TODO: 从订单簿或缓存中获取最新成交价
        return null;
    }

    @Override
    public String getName() {
        return "PriceRangeChecker";
    }

    /**
     * 价格范围配置
     */
    private static class PriceRange {
        final BigDecimal min;
        final BigDecimal max;

        PriceRange(BigDecimal min, BigDecimal max) {
            this.min = min;
            this.max = max;
        }
    }
}
