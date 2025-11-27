package com.matching.api.dto;

import com.matching.core.domain.OrderType;
import com.matching.core.domain.Side;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class OrderRequest {
    private String orderId;
    private String symbol;
    private Side side;
    private OrderType type;
    private BigDecimal price;
    private BigDecimal quantity;

}