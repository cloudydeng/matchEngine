package com.matching.core.engine;

import com.matching.core.domain.DepthLevel;
import com.matching.core.domain.Order;
import com.matching.core.domain.Trade;
import com.matching.core.persistence.OrderBookPersistence;
import com.matching.disruptor.MarketDataPublisher;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;


import java.io.IOException;
import java.util.List;

@Slf4j
public class MatchingEngine {
    @Getter
    private final String symbol;                    // 关键：这个引擎只管这个 symbol
    private final L3OrderBook orderBook;
    private final OrderBookPersistence persistence;
    private final MarketDataPublisher publisher;


    public MatchingEngine(String symbol,MarketDataPublisher publisher) throws IOException {
        this.symbol = symbol;
        this.orderBook = new L3OrderBook(symbol,publisher);
        this.persistence = new OrderBookPersistence(orderBook, symbol);
        this.publisher = publisher;
    }



    /**
     * 提交订单（统一入口）
     */
    public List<Trade> submitOrder(Order order) {
        // 强制绑定 symbol，防止用户传错
        order.setSymbol(symbol);
        if (order.getOrderId() == null || order.getOrderId().isBlank()) {
            order.setOrderId(symbol + "_" + System.nanoTime());
        }

        // 自动打时间戳（价格时间优先级关键！）
        order.setTimestamp(System.nanoTime());

        return orderBook.processOrder(order);
    }

    /**
     * 撤单（推荐用 orderId 撤单，这是生产唯一正确方式）
     */
    public boolean cancelOrder(String orderId) {
        return orderBook.cancelOrder(orderId);
    }

    /**
     * 兼容旧接口：用 clientOrderId 撤单（Binance 也支持）
     */
    public boolean cancelOrderByClientOrderId(String clientOrderId) {
        // 实际生产要维护 clientOrderId → orderId 映射，这里简化
        return false;
    }

    // ==================== 行情接口（给 WebSocket 推送）===================
    public List<DepthLevel> getDepth(int levels) {
        return orderBook.getDepth(levels);
    }

}