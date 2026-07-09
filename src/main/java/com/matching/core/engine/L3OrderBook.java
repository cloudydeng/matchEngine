
package com.matching.core.engine;

import com.matching.core.domain.*;
import com.matching.disruptor.OrderEventHandler;
import com.matching.disruptor.MarketDataPublisher;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public final class L3OrderBook {

    private final String symbol;
    private final MarketDataPublisher publisher;
    private final AtomicLong seq = new AtomicLong(0);

    private final ConcurrentSkipListMap<BigDecimal, PriceLevel> bids = new ConcurrentSkipListMap<>(Comparator.reverseOrder());
    private final ConcurrentSkipListMap<BigDecimal, PriceLevel> asks = new ConcurrentSkipListMap<>(Comparator.naturalOrder());
    private final ConcurrentHashMap<String, OrderEntry> orderIndex = new ConcurrentHashMap<>();

    private volatile boolean fiveLevelProtection = false;
    private static final int MAX_LEVELS = 30;

    public L3OrderBook(String symbol, MarketDataPublisher publisher) {
        this.symbol = symbol;
        this.publisher = publisher;
        log.info("L3OrderBook 初始化完成: {}", symbol);
    }


    private void fireDepthUpdate(BigDecimal price, BigDecimal newQty, Side side) {
        if (publisher != null && price != null && side != null) {
            boolean isBid = side == Side.BUY;
            BigDecimal qty = (newQty == null || newQty.signum() < 0) ? BigDecimal.ZERO : newQty;
            publisher.publishUpdate(symbol, price, qty, isBid);
        }
    }

    private static class PriceLevel {
        final TreeMap<Long, OrderEntry> orders = new TreeMap<>();
        BigDecimal totalQty = BigDecimal.ZERO;
    }

    private static class OrderEntry {
        final Order order;
        final long ts;
        final BigDecimal price;
        BigDecimal remain;
        PriceLevel level;

        OrderEntry(Order order, long ts) {
            this.order = order;
            this.ts = ts;
            this.price = order.getPrice();
            this.remain = order.getQuantity();
        }
    }

    public static class SnapshotOrder {
        public final String orderId;
        public final String clientOrderId;
        public final String userId;
        public final Side side;
        public final OrderType type;
        public final BigDecimal price;
        public final BigDecimal remain;
        public final long timestamp;

        public SnapshotOrder(String orderId, String clientOrderId, String userId, Side side, OrderType type,
                             BigDecimal price, BigDecimal remain, long timestamp) {
            this.orderId = orderId;
            this.clientOrderId = clientOrderId;
            this.userId = userId;
            this.side = side;
            this.type = type;
            this.price = price;
            this.remain = remain;
            this.timestamp = timestamp;
        }
    }

    public synchronized void restoreFromSnapshot(List<Map.Entry<BigDecimal, BigDecimal>> bidLevels,
                                                 List<Map.Entry<BigDecimal, BigDecimal>> askLevels) {
        bids.clear();
        asks.clear();
        orderIndex.clear();

        if (bidLevels != null) {
            for (Map.Entry<BigDecimal, BigDecimal> level : bidLevels) {
                addSnapshotLevel(Side.BUY, level.getKey(), level.getValue());
            }
        }
        if (askLevels != null) {
            for (Map.Entry<BigDecimal, BigDecimal> level : askLevels) {
                addSnapshotLevel(Side.SELL, level.getKey(), level.getValue());
            }
        }
        log.info("OrderBook restored from snapshot: symbol={}, bidLevels={}, askLevels={}",
                symbol, bidLevels != null ? bidLevels.size() : 0, askLevels != null ? askLevels.size() : 0);
    }

    public synchronized void restoreFromSnapshotOrders(List<SnapshotOrder> snapshotOrders) {
        bids.clear();
        asks.clear();
        orderIndex.clear();

        if (snapshotOrders == null) {
            return;
        }

        for (SnapshotOrder so : snapshotOrders) {
            if (so == null || so.price == null || so.remain == null || so.remain.signum() <= 0) {
                continue;
            }
            ConcurrentSkipListMap<BigDecimal, PriceLevel> book = so.side == Side.BUY ? bids : asks;
            PriceLevel level = book.computeIfAbsent(so.price, k -> new PriceLevel());

            Order order = new Order();
            order.setOrderId(so.orderId);
            order.setClientOrderId(so.clientOrderId);
            order.setUserId(so.userId);
            order.setSymbol(symbol);
            order.setSide(so.side);
            order.setType(so.type != null ? so.type : OrderType.LIMIT);
            order.setPrice(so.price);
            order.setQuantity(so.remain);
            order.setTimestamp(so.timestamp);

            long ts = so.timestamp > 0 ? so.timestamp : System.nanoTime();
            while (level.orders.containsKey(ts)) {
                ts++;
            }

            OrderEntry entry = new OrderEntry(order, ts);
            entry.remain = so.remain;
            entry.level = level;

            level.orders.put(ts, entry);
            level.totalQty = level.totalQty.add(so.remain);
            orderIndex.put(so.orderId, entry);
            if (so.clientOrderId != null && !so.clientOrderId.isBlank()) {
                OrderEventHandler.getClientOrderIndex().put(symbol, so.clientOrderId, so.orderId);
            }
        }

        log.info("OrderBook restored from order snapshot: symbol={}, orders={}",
                symbol, snapshotOrders.size());
    }

    public synchronized List<SnapshotOrder> getOrdersForSnapshot() {
        List<SnapshotOrder> result = new ArrayList<>(orderIndex.size());
        for (OrderEntry entry : orderIndex.values()) {
            if (entry == null || entry.remain == null || entry.remain.signum() <= 0) {
                continue;
            }
            result.add(new SnapshotOrder(
                    entry.order.getOrderId(),
                    entry.order.getClientOrderId(),
                    entry.order.getUserId(),
                    entry.order.getSide(),
                    entry.order.getType(),
                    entry.price,
                    entry.remain,
                    entry.ts
            ));
        }
        return result;
    }

    public synchronized void replayOrder(Order order) {
        if (order == null) {
            return;
        }
        processOrder(order);
    }

    public synchronized boolean replayCancel(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return false;
        }
        return cancelOrder(orderId);
    }

    private void addSnapshotLevel(Side side, BigDecimal price, BigDecimal qty) {
        if (price == null || qty == null || qty.signum() <= 0) {
            return;
        }
        ConcurrentSkipListMap<BigDecimal, PriceLevel> book = side == Side.BUY ? bids : asks;
        PriceLevel level = book.computeIfAbsent(price, k -> new PriceLevel());

        String syntheticOrderId = "SNAPSHOT_" + symbol + "_" + side + "_" + seq.incrementAndGet();
        Order syntheticOrder = new Order();
        syntheticOrder.setOrderId(syntheticOrderId);
        syntheticOrder.setSymbol(symbol);
        syntheticOrder.setSide(side);
        syntheticOrder.setType(OrderType.LIMIT);
        syntheticOrder.setPrice(price);
        syntheticOrder.setQuantity(qty);
        syntheticOrder.setTimestamp(System.nanoTime());

        long ts = System.nanoTime();
        OrderEntry entry = new OrderEntry(syntheticOrder, ts);
        entry.remain = qty;
        entry.level = level;

        level.orders.put(ts, entry);
        level.totalQty = level.totalQty.add(qty);
        orderIndex.put(syntheticOrderId, entry);
    }

    public synchronized List<Trade> processOrder(Order order) {
        if (order == null) {
            return List.of();
        }
        if (order.getSide() == null) {
            order.setStatus(OrderStatus.REJECTED);
            order.setRejectReason("INVALID_SIDE");
            return List.of();
        }
        if (order.getType() == null || (order.getType() != OrderType.LIMIT && order.getType() != OrderType.MARKET)) {
            order.setStatus(OrderStatus.REJECTED);
            order.setRejectReason("UNSUPPORTED_ORDER_TYPE");
            return List.of();
        }
        if (order.getQuantity() == null || order.getQuantity().signum() <= 0) {
            order.setStatus(OrderStatus.REJECTED);
            order.setRejectReason("INVALID_QUANTITY");
            log.warn("Reject order {}: invalid quantity", order.getOrderId());
            return List.of();
        }
        if (order.getType() == OrderType.LIMIT
                && (order.getPrice() == null || order.getPrice().signum() <= 0)) {
            order.setStatus(OrderStatus.REJECTED);
            order.setRejectReason("INVALID_PRICE");
            log.warn("Reject order {}: invalid price", order.getOrderId());
            return List.of();
        }

        order.setSymbol(symbol);
        order.setOrderId(order.getOrderId() == null ? symbol + "_" + seq.incrementAndGet() : order.getOrderId());
        order.setTimestamp(System.nanoTime());

        try {
            return order.isMarketOrder() ? matchMarket(order) : matchLimit(order);
        } catch (Exception e) {
            log.error("Unexpected error processing order {}", order.getOrderId(), e);
            order.setStatus(OrderStatus.REJECTED);
            order.setRejectReason("SYSTEM_ERROR");
            return List.of();
        }
    }

    private List<Trade> matchMarket(Order mo) {
        List<Trade> trades = new ArrayList<>();
        BigDecimal remain = mo.getQuantity();
        var opposite = mo.getSide() == Side.BUY ? asks : bids;
        Side makerSide = mo.getSide() == Side.BUY ? Side.SELL : Side.BUY;

        if (fiveLevelProtection && wouldSweepMarketBeyondFiveLevels(remain, mo.getSide())) {
            log.warn("Market order {} rejected: exceed 5 levels", mo.getOrderId());
            mo.setStatus(OrderStatus.REJECTED);
            mo.setRejectReason("EXCEED_FIVE_LEVELS");
            return trades;
        }

        var iter = mo.getSide() == Side.BUY ? opposite.keySet().iterator() : opposite.descendingKeySet().iterator();

        while (iter.hasNext() && remain.signum() > 0) {
            BigDecimal price = iter.next();
            PriceLevel levelData = opposite.get(price);
            if (levelData == null || levelData.orders.isEmpty()) {
                iter.remove();
                fireDepthUpdate(price, BigDecimal.ZERO, makerSide);
                continue;
            }

            var orderIter = levelData.orders.values().iterator();

            while (orderIter.hasNext() && remain.signum() > 0) {
                OrderEntry maker = orderIter.next();
                BigDecimal fill = remain.min(maker.remain);

                trades.add(new Trade(symbol, mo.getSide(), price, fill, mo.getOrderId(), maker.order.getOrderId()));
                remain = remain.subtract(fill);
                maker.remain = maker.remain.subtract(fill);
                maker.order.addFilledQuantity(fill);
                mo.addFilledQuantity(fill);

                if (maker.remain.signum() == 0) {
                    orderIter.remove();
                    orderIndex.remove(maker.order.getOrderId());
                }
            }

            levelData.totalQty = levelData.orders.values().stream()
                    .map(e -> e.remain)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            fireDepthUpdate(price, levelData.totalQty, makerSide);

            if (levelData.orders.isEmpty()) {
                iter.remove();
                fireDepthUpdate(price, BigDecimal.ZERO, makerSide);
            }
        }

        mo.setStatus(remain.signum() > 0
                ? (mo.getFilledQuantity().signum() > 0 ? OrderStatus.PARTIALLY_FILLED : OrderStatus.REJECTED)
                : OrderStatus.FILLED);
        return trades;
    }

    private List<Trade> matchLimit(Order lo) {
        List<Trade> trades = new ArrayList<>();
        BigDecimal remain = lo.getQuantity();
        Side side = lo.getSide();
        Side makerSide = side == Side.BUY ? Side.SELL : Side.BUY;
        BigDecimal limit = lo.getPrice();
        var opposite = side == Side.BUY ? asks : bids;

        if (fiveLevelProtection && wouldSweepFiveLevels(remain, side, limit)) {
            log.warn("Limit order {} rejected: would sweep 5 levels", lo.getOrderId());
            lo.setStatus(OrderStatus.REJECTED);
            lo.setRejectReason("SWEEP_FIVE_LEVELS");
            return trades;
        }

        var iter = side == Side.BUY ? opposite.keySet().iterator() : opposite.descendingKeySet().iterator();

        while (iter.hasNext() && remain.signum() > 0) {
            BigDecimal price = iter.next();
            if (side == Side.BUY && price.compareTo(limit) > 0) break;
            if (side == Side.SELL && price.compareTo(limit) < 0) break;

            PriceLevel level = opposite.get(price);
            if (level == null || level.orders.isEmpty()) {
                iter.remove();
                fireDepthUpdate(price, BigDecimal.ZERO, makerSide);
                continue;
            }

            var orderIter = level.orders.values().iterator();

            while (orderIter.hasNext() && remain.signum() > 0) {
                OrderEntry maker = orderIter.next();
                if (maker.order.getUserId() != null && maker.order.getUserId().equals(lo.getUserId())) {
                    continue;
                }

                BigDecimal fill = remain.min(maker.remain);
                trades.add(new Trade(symbol, lo.getSide(), price, fill, lo.getOrderId(), maker.order.getOrderId()));

                remain = remain.subtract(fill);
                maker.remain = maker.remain.subtract(fill);
                maker.order.addFilledQuantity(fill);
                lo.addFilledQuantity(fill);

                if (maker.remain.signum() == 0) {
                    orderIter.remove();
                    orderIndex.remove(maker.order.getOrderId());
                }
            }

            level.totalQty = level.orders.values().stream().map(e -> e.remain).reduce(BigDecimal.ZERO, BigDecimal::add);
            fireDepthUpdate(price, level.totalQty, makerSide);

            if (level.orders.isEmpty()) {
                iter.remove();
                fireDepthUpdate(price, BigDecimal.ZERO, makerSide);
            }
        }

        if (remain.signum() > 0) {
            addToBook(lo, remain);
            lo.setStatus(lo.getFilledQuantity().signum() > 0 ? OrderStatus.PARTIALLY_FILLED : OrderStatus.NEW);
            // 挂单成功也推一次
            fireDepthUpdate(lo.getPrice(), remain, lo.getSide());
        } else {
            lo.setStatus(OrderStatus.FILLED);
        }

        return trades;
    }

    private boolean wouldSweepFiveLevels(BigDecimal qty, Side side, BigDecimal limit) {
        var book = side == Side.BUY ? asks : bids;
        BigDecimal remain = qty;
        int matchingLevels = 0;
        for (BigDecimal p : side == Side.BUY ? book.keySet() : book.descendingKeySet()) {
            if (side == Side.BUY && p.compareTo(limit) > 0) break;
            if (side == Side.SELL && p.compareTo(limit) < 0) break;
            PriceLevel level = book.get(p);
            if (level == null || level.totalQty.signum() <= 0) {
                continue;
            }
            matchingLevels++;
            remain = remain.subtract(level.totalQty);
            if (remain.signum() <= 0) return false;
            if (matchingLevels >= MAX_LEVELS) return true;
        }
        return false;
    }

    private boolean wouldSweepMarketBeyondFiveLevels(BigDecimal qty, Side side) {
        var book = side == Side.BUY ? asks : bids;
        BigDecimal remain = qty;
        int matchingLevels = 0;
        for (BigDecimal p : side == Side.BUY ? book.keySet() : book.descendingKeySet()) {
            PriceLevel level = book.get(p);
            if (level == null || level.totalQty.signum() <= 0) {
                continue;
            }
            matchingLevels++;
            remain = remain.subtract(level.totalQty);
            if (remain.signum() <= 0) return false;
            if (matchingLevels >= MAX_LEVELS) return true;
        }
        return false;
    }

    private void addToBook(Order order, BigDecimal qty) {
        var book = order.getSide() == Side.BUY ? bids : asks;
        PriceLevel level = book.computeIfAbsent(order.getPrice(), k -> new PriceLevel());
        long ts = System.nanoTime();

        OrderEntry entry = new OrderEntry(order, ts);
        entry.remain = qty;
        entry.level = level;

        level.orders.put(ts, entry);
        level.totalQty = level.totalQty.add(qty);
        orderIndex.put(order.getOrderId(), entry);
    }

    public synchronized boolean cancelOrder(String orderId) {
        OrderEntry e = orderIndex.remove(orderId);
        if (e == null || e.remain.signum() <= 0) return false;

        e.level.orders.remove(e.ts);
        e.level.totalQty = e.level.totalQty.subtract(e.remain);

        fireDepthUpdate(e.price, e.level.totalQty, e.order.getSide());

        if (e.level.orders.isEmpty()) {
            (e.order.getSide() == Side.BUY ? bids : asks).remove(e.price);
            fireDepthUpdate(e.price, BigDecimal.ZERO, e.order.getSide());
        }
        return true;
    }

    // getDepth、snapshot 方法保持不变...
    public synchronized List<DepthLevel> getDepth(int levels) {
        List<DepthLevel> list = new ArrayList<>();
        int c = 0;
        for (var e : bids.entrySet()) { if (++c > levels) break; list.add(new DepthLevel(e.getKey(), e.getValue().totalQty)); }
        c = 0;
        for (var e : asks.entrySet()) { if (++c > levels) break; list.add(new DepthLevel(e.getKey(), e.getValue().totalQty)); }
        return list;
    }

    public synchronized List<Map.Entry<BigDecimal, BigDecimal>> getBidsForSnapshot() {
        return bids.entrySet().stream()
                .map(e -> Map.entry(e.getKey(), e.getValue().totalQty))
                .toList();
    }

    public synchronized List<Map.Entry<BigDecimal, BigDecimal>> getAsksForSnapshot() {
        return asks.entrySet().stream()
                .map(e -> Map.entry(e.getKey(), e.getValue().totalQty))
                .toList();
    }
}
