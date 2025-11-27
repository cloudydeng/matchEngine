package com.matching.core.domain;

public enum OrderStatus {
    NEW,                // 新创建
    PARTIALLY_FILLED,   // 部分成交
    FILLED,             // 完全成交
    CANCELED,           // 已撤销（用户主动或 IOC/FOK 剩余取消）
    REJECTED,           // 下单就拒绝（风控、五档保护等）
    EXPIRED
}
