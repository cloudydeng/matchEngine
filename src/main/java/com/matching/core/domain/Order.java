package com.matching.core.domain;


import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data
public class Order {
    private String orderId;                    // 系统生成的唯一ID（如 BTCUSDT_182739471）
    private String clientOrderId;              // 用户自己传的ID（可选，用于撤单对齐）
    private String symbol;                     // 交易对：BTCUSDT
    private Side side;                         // BUY / SELL
    private OrderType type;                    // LIMIT, MARKET, STOP_LIMIT, etc.
    private BigDecimal price;                  // 限价单价格，市价单可为 null 或 0
    private BigDecimal stopPrice;              // 止损/条件单触发价格（可选）
    private BigDecimal quantity;               // 下单数量（原始）
    private BigDecimal filledQuantity = BigDecimal.ZERO;   // 已成交数量
    private BigDecimal remainingQuantity;      // 剩余未成交（方便快速判断）


    private long timestamp;                    // 纳秒级时间戳，用于价格时间优先级排序（关键！）
    private Instant createTime;                // 可读时间，用于日志/审计
    private Instant updateTime;


    private String userId;                     // 用户ID，必备！用于自成交预防、费率、限仓
    private String accountId;                  // 子账户（可选）

    // ==================== 高级订单类型支持 ====================
    private TimeInForce timeInForce = TimeInForce.GTC;  // GTC, IOC, FOK, POST_ONLY
    private boolean postOnly = false;          // 是否只挂单（Maker-Only）
    private boolean reduceOnly = false;        // 减仓单（合约必备）
    private boolean hidden = false;            // 隐藏单（不进深度）
    private BigDecimal displayQuantity;        // 冰山单显示数量（null = 普通单）

    // ==================== 状态管理 ====================
    private OrderStatus status = OrderStatus.NEW;
    private String rejectReason;               // 被拒绝原因

    // ==================== 成交统计 ====================
    private BigDecimal cumQuoteQty = BigDecimal.ZERO;   // 已成交金额（张数 × 价格）
    private BigDecimal avgFillPrice = BigDecimal.ZERO;  // 平均成交价

    // ==================== 构造器 & 便捷方法 ====================
    public Order() {
        this.createTime = Instant.now();
        this.updateTime = Instant.now();
    }

    public void addFilledQuantity(BigDecimal qty) {
        this.filledQuantity = this.filledQuantity.add(qty);
        this.remainingQuantity = this.quantity.subtract(this.filledQuantity);
        this.cumQuoteQty = this.cumQuoteQty.add(qty.multiply(price != null ? price : BigDecimal.ZERO));
        this.updateTime = Instant.now();
        updateStatus();
    }

    private void updateStatus() {
        if (filledQuantity.compareTo(BigDecimal.ZERO) == 0) {
            status = OrderStatus.NEW;
        } else if (remainingQuantity.signum() <= 0) {
            status = OrderStatus.FILLED;
        } else {
            status = OrderStatus.PARTIALLY_FILLED;
        }
    }

    public boolean isFilled() {
        return status == OrderStatus.FILLED || remainingQuantity.signum() <= 0;
    }

    public boolean isMarketOrder() {
        return type == OrderType.MARKET;
    }


    public String toWalString() {
        // WAL 格式标准：用制表符 \t 分隔，容易解析，体积最小
        // 字段顺序固定，重启恢复时按顺序读就行
        return String.join("\t",
                "ORDER",                                      // 1. 操作类型
                nonNull(orderId),                             // 2. 系统订单ID
                nonNull(clientOrderId),                       // 3. 用户自定义ID
                nonNull(symbol),                              // 4. 交易对
                side.name(),                                  // 5. BUY/SELL
                type.name(),                                  // 6. LIMIT/MARKET/...
                nonNull(price),                               // 7. 价格（市价单为 0）
                nonNull(stopPrice),                           // 8. 触发价（条件单）
                nonNull(quantity),                            // 9. 原始数量
                nonNull(userId),                              // 10. 用户ID（防自成交必备）
                String.valueOf(timestamp),                    // 11. 纳秒时间戳（排序关键！）
                timeInForce.name(),                           // 12. GTC/IOC/FOK/POST_ONLY
                bool(postOnly),                               // 13. 是否postOnly
                bool(reduceOnly),                             // 14. 是否减仓单
                bool(hidden),                                 // 15. 是否隐藏单
                nonNull(displayQuantity),                     // 16. 冰山单显示数量
                String.valueOf(createTime.toEpochMilli())     // 17. 创建时间（可读）
        );
    }


    private String nonNull(Object o) {
        return o == null ? "" : o.toString();
    }

    private String bool(boolean b) {
        return b ? "1" : "0";
    }


}