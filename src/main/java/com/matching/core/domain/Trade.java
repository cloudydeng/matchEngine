package com.matching.core.domain;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class Trade {
    private String tradeId;
    private String symbol;
    private Side side;
    private BigDecimal price;
    private BigDecimal quantity;
    private String buyOrderId;
    private String sellOrderId;

    // 构造函数、getter/setter 省略
    public Trade(String symbol, Side side, BigDecimal price, BigDecimal quantity, String buyOrderId, String sellOrderId) {
        this.tradeId = java.util.UUID.randomUUID().toString();
        this.symbol = symbol;
        this.side = side;
        this.price = price;
        this.quantity = quantity;
        this.buyOrderId = buyOrderId;
        this.sellOrderId = sellOrderId;
    }

}