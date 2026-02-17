package com.matching.api.dto;

import lombok.Data;

@Data
public class CancelRequest {
    private String orderId;          // 系统生成的订单ID
    private String clientOrderId;    // 用户自定义订单ID（二选一）
    private String symbol;           // 交易对（必需）
}