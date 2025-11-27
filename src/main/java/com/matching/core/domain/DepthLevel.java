package com.matching.core.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 深度行情（L2 盘口）每一档的数据结构
 * 用于：
 *   - WebSocket 实时深度推送
 *   - REST API 返回 /api/v5/market/books
 *   - 前端深度图渲染
 */
public record DepthLevel(
        BigDecimal price,      // 价格（必填）
        BigDecimal quantity    // 该价格下总挂单数量（已聚合，必填）
) implements Comparable<DepthLevel> {

    // 构造器：防止 null
    public DepthLevel {
        Objects.requireNonNull(price, "price cannot be null");
        Objects.requireNonNull(quantity, "quantity cannot be null");
        if (price.signum() <= 0) throw new IllegalArgumentException("price must > 0");
        if (quantity.signum() < 0) throw new IllegalArgumentException("quantity cannot be negative");
    }

    // ==================== 常见快捷构造器 ====================
    public static DepthLevel of(String price, String quantity) {
        return new DepthLevel(new BigDecimal(price), new BigDecimal(quantity));
    }

    public static DepthLevel of(double price, double quantity) {
        return new DepthLevel(
                BigDecimal.valueOf(price),
                BigDecimal.valueOf(quantity)
        );
    }

    // ==================== 用于排序（买盘降序，卖盘升序） ====================
    @Override
    public int compareTo(DepthLevel o) {
        return this.price.compareTo(o.price);
    }

    // ==================== 兼容各种交易所字段名 ====================

    // Binance 风格（字段名 p, q）
    public String[] toBinanceArray() {
        return new String[]{
                price.stripTrailingZeros().toPlainString(),
                quantity.stripTrailingZeros().toPlainString()
        };
    }

    // OKX 风格（4个字段，最后是订单笔数，这里填0或实际值）
    public String[] toOkxArray() {
        return new String[]{
                price.stripTrailingZeros().toPlainString(),
                quantity.stripTrailingZeros().toPlainString(),
                "0",   // 强度（可忽略）
                "1"    // 订单笔数（L2聚合后通常是1或真实笔数）
        };
    }

    // 标准 getter（给 Jackson/Gson 序列化用）
    public BigDecimal getPrice() { return price; }
    public BigDecimal getQuantity() { return quantity; }

    // 方便打印
    @Override
    public String toString() {
        return String.format("%s @ %s", quantity.stripTrailingZeros(), price.stripTrailingZeros());
    }

    // 判断是否为空档（quantity == 0 用于删除档位）
    public boolean isZero() {
        return quantity.signum() == 0;
    }
}