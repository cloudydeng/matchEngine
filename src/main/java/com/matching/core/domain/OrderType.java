package com.matching.core.domain;

public enum OrderType {
    LIMIT,           // 限价单
    MARKET,          // 市价单
    STOP_LIMIT,      // 条件限价单
    STOP_MARKET,     // 条件市价单
    TAKE_PROFIT,     // 止盈单
    TAKE_PROFIT_MARKET
}
