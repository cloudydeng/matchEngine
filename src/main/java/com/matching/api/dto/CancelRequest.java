package com.matching.api.dto;

import lombok.Data;

@Data
public class CancelRequest {
    private String orderId;
    private String clientOrderId;
    private String symbol;
}
