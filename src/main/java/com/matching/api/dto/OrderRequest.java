package com.matching.api.dto;

import com.matching.core.domain.OrderType;
import com.matching.core.domain.Side;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class OrderRequest {
    private String orderId;
    private String clientOrderId;  // 用户自定义订单ID，用于撤单
    private String symbol;
    private Side side;
    private OrderType type;
    private BigDecimal price;
    private BigDecimal quantity;
    private String userId;           // 用户ID，用于自成交预防
}