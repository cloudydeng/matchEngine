// L3OrderBook.java —— 2025 年生产级终极版（已集成行情推送 + 防 NPE + 5 个触发点全覆盖）
package com.matching.core.engine;

import com.matching.core.domain.*;
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

    private volatile boolean fiveLevelProtection = true;
    private static final int MAX_LEVELS = 5;

    public L3OrderBook(String symbol, MarketDataPublisher publisher) {
        this.symbol = symbol;
        this.publisher = publisher;
        log.info("L3OrderBook 初始化完成: {}", symbol);
    }

    // 安全推送（防 NPE 终极版）
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

    public List<Trade> processOrder(Order order) {
        if (order.getQuantity() == null || order.getQuantity().signum() <= 0) {
            order.setStatus(OrderStatus.REJECTED);
            order.setRejectReason("INVALID_QUANTITY");
            log.warn("Reject order {}: invalid quantity", order.getOrderId());
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

        int level = 0;
        var iter = mo.getSide() == Side.BUY ? opposite.keySet().iterator() : opposite.descendingKeySet().iterator();

        while (iter.hasNext() && remain.signum() > 0) {
            if (fiveLevelProtection && ++level > MAX_LEVELS) {
                log.warn("Market order {} rejected: exceed 5 levels", mo.getOrderId());
                mo.setStatus(OrderStatus.REJECTED);
                mo.setRejectReason("EXCEED_FIVE_LEVELS");
                break;
            }

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

        mo.setStatus(remain.signum() > 0 ? OrderStatus.REJECTED : OrderStatus.FILLED);
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
        BigDecimal acc = BigDecimal.ZERO;
        int count = 0;
        for (BigDecimal p : side == Side.BUY ? book.keySet() : book.descendingKeySet()) {
            if (count++ >= MAX_LEVELS) break;
            if (side == Side.BUY && p.compareTo(limit) > 0) break;
            if (side == Side.SELL && p.compareTo(limit) < 0) break;
            acc = acc.add(book.get(p).totalQty);
            if (acc.compareTo(qty) >= 0) return false;
        }
        return true;
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

    public boolean cancelOrder(String orderId) {
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
    public List<DepthLevel> getDepth(int levels) {
        List<DepthLevel> list = new ArrayList<>();
        int c = 0;
        for (var e : bids.entrySet()) { if (++c > levels) break; list.add(new DepthLevel(e.getKey(), e.getValue().totalQty)); }
        c = 0;
        for (var e : asks.entrySet()) { if (++c > levels) break; list.add(new DepthLevel(e.getKey(), e.getValue().totalQty)); }
        return list;
    }

    public List<Map.Entry<BigDecimal, BigDecimal>> getBidsForSnapshot() {
        return bids.entrySet().stream()
                .map(e -> Map.entry(e.getKey(), e.getValue().totalQty))
                .toList();
    }

    public List<Map.Entry<BigDecimal, BigDecimal>> getAsksForSnapshot() {
        return asks.entrySet().stream()
                .map(e -> Map.entry(e.getKey(), e.getValue().totalQty))
                .toList();
    }
}