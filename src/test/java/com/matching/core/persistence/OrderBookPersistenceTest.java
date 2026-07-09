package com.matching.core.persistence;

import com.matching.core.domain.Order;
import com.matching.core.domain.OrderType;
import com.matching.core.domain.Side;
import com.matching.core.engine.L3OrderBook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OrderBookPersistenceTest {

    @TempDir
    Path tempDir;

    @Test
    void replaysWalIntoEmptyBook() throws Exception {
        Path snapshots = tempDir.resolve("snapshots");
        Path wal = tempDir.resolve("wal");
        Order order = limit("buy-1", Side.BUY, "99", "3");

        L3OrderBook book = new L3OrderBook("BTCUSDT", null);
        OrderBookPersistence persistence = new OrderBookPersistence(book, "BTCUSDT", snapshots, wal);
        persistence.appendSubmit(order);
        persistence.shutdown();

        L3OrderBook recovered = new L3OrderBook("BTCUSDT", null);
        OrderBookPersistence recoveredPersistence = new OrderBookPersistence(recovered, "BTCUSDT", snapshots, wal);

        assertEquals(1, recovered.getDepth(10).size());
        assertEquals(0, recovered.getDepth(10).get(0).price().compareTo(new BigDecimal("99")));
        assertEquals(0, recovered.getDepth(10).get(0).quantity().compareTo(new BigDecimal("3")));
        recoveredPersistence.shutdown();
    }

    @Test
    void loadsSnapshotAndTruncatesWal() throws Exception {
        Path snapshots = tempDir.resolve("snapshots");
        Path wal = tempDir.resolve("wal");
        L3OrderBook book = new L3OrderBook("BTCUSDT", null);
        OrderBookPersistence persistence = new OrderBookPersistence(book, "BTCUSDT", snapshots, wal);

        Order order = limit("sell-1", Side.SELL, "101", "2");
        persistence.appendSubmit(order);
        book.processOrder(order);
        persistence.forceSnapshot();
        persistence.shutdown();

        L3OrderBook recovered = new L3OrderBook("BTCUSDT", null);
        OrderBookPersistence recoveredPersistence = new OrderBookPersistence(recovered, "BTCUSDT", snapshots, wal);

        assertEquals(1, recovered.getDepth(10).size());
        assertEquals(0, recovered.getDepth(10).get(0).price().compareTo(new BigDecimal("101")));
        assertEquals(0, recovered.getDepth(10).get(0).quantity().compareTo(new BigDecimal("2")));
        recoveredPersistence.shutdown();
    }

    private Order limit(String orderId, Side side, String price, String quantity) {
        Order order = new Order();
        order.setOrderId(orderId);
        order.setSymbol("BTCUSDT");
        order.setSide(side);
        order.setType(OrderType.LIMIT);
        order.setPrice(new BigDecimal(price));
        order.setQuantity(new BigDecimal(quantity));
        return order;
    }
}
