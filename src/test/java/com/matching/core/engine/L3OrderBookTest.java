package com.matching.core.engine;

import com.matching.core.domain.DepthLevel;
import com.matching.core.domain.Order;
import com.matching.core.domain.OrderStatus;
import com.matching.core.domain.OrderType;
import com.matching.core.domain.Side;
import com.matching.core.domain.Trade;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class L3OrderBookTest {

    @Test
    void limitOrderOnEmptyBookIsAcceptedAsMaker() {
        L3OrderBook book = new L3OrderBook("BTCUSDT", null);
        Order order = limit("sell-1", Side.SELL, "100", "2");

        List<Trade> trades = book.processOrder(order);

        assertTrue(trades.isEmpty());
        assertEquals(OrderStatus.NEW, order.getStatus());
        assertDepth(book, "100", "2");
    }

    @Test
    void crossingLimitOrderMatchesRestingOrder() {
        L3OrderBook book = new L3OrderBook("BTCUSDT", null);
        Order sell = limit("sell-1", Side.SELL, "100", "2");
        Order buy = limit("buy-1", Side.BUY, "101", "2");

        book.processOrder(sell);
        List<Trade> trades = book.processOrder(buy);

        assertEquals(1, trades.size());
        assertEquals(0, trades.get(0).getPrice().compareTo(new BigDecimal("100")));
        assertEquals(0, trades.get(0).getQuantity().compareTo(new BigDecimal("2")));
        assertEquals(OrderStatus.FILLED, buy.getStatus());
        assertEquals(OrderStatus.FILLED, sell.getStatus());
        assertTrue(book.getDepth(10).isEmpty());
    }

    @Test
    void cancelOrderRemovesRestingDepth() {
        L3OrderBook book = new L3OrderBook("BTCUSDT", null);
        Order buy = limit("buy-1", Side.BUY, "99", "3");

        book.processOrder(buy);
        boolean cancelled = book.cancelOrder("buy-1");

        assertTrue(cancelled);
        assertTrue(book.getDepth(10).isEmpty());
        assertFalse(book.cancelOrder("buy-1"));
    }

    @Test
    void marketOrderCanPartiallyFillAvailableLiquidity() {
        L3OrderBook book = new L3OrderBook("BTCUSDT", null);
        Order sell = limit("sell-1", Side.SELL, "100", "1");
        Order buy = market("buy-1", Side.BUY, "2");

        book.processOrder(sell);
        List<Trade> trades = book.processOrder(buy);

        assertEquals(1, trades.size());
        assertEquals(0, trades.get(0).getQuantity().compareTo(new BigDecimal("1")));
        assertEquals(OrderStatus.PARTIALLY_FILLED, buy.getStatus());
        assertEquals(OrderStatus.FILLED, sell.getStatus());
        assertTrue(book.getDepth(10).isEmpty());
    }

    private Order limit(String orderId, Side side, String price, String quantity) {
        Order order = new Order();
        order.setOrderId(orderId);
        order.setSide(side);
        order.setType(OrderType.LIMIT);
        order.setPrice(new BigDecimal(price));
        order.setQuantity(new BigDecimal(quantity));
        return order;
    }

    private Order market(String orderId, Side side, String quantity) {
        Order order = new Order();
        order.setOrderId(orderId);
        order.setSide(side);
        order.setType(OrderType.MARKET);
        order.setQuantity(new BigDecimal(quantity));
        return order;
    }

    private void assertDepth(L3OrderBook book, String price, String quantity) {
        List<DepthLevel> depth = book.getDepth(10);
        assertEquals(1, depth.size());
        assertEquals(0, depth.get(0).price().compareTo(new BigDecimal(price)));
        assertEquals(0, depth.get(0).quantity().compareTo(new BigDecimal(quantity)));
    }
}
