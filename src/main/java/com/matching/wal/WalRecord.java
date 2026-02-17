package com.matching.wal;

import com.matching.core.domain.*;
import lombok.Data;

import java.math.BigDecimal;

/**
 * WAL 记录格式
 * 支持订单提交、撤单、成交三种操作类型
 */
@Data
public class WalRecord {

    /**
     * WAL 操作类型
     */
    public enum WalType {
        ORDER_SUBMIT,    // 订单提交
        ORDER_CANCEL,    // 订单取消
        TRADE,           // 成交记录
        SNAPSHOT         // 快照标记
    }

    private WalType type;
    private long sequence;           // 序列号
    private long timestamp;          // 毫秒时间戳

    // 订单相关字段
    private String orderId;
    private String clientOrderId;
    private String symbol;
    private Side side;
    private OrderType orderType;
    private BigDecimal price;
    private BigDecimal quantity;
    private String userId;

    // 交易相关字段
    private String buyOrderId;
    private String sellOrderId;
    private BigDecimal tradePrice;
    private BigDecimal tradeQuantity;

    /**
     * 创建订单提交记录
     */
    public static WalRecord createOrderSubmit(Order order, long sequence) {
        WalRecord record = new WalRecord();
        record.type = WalType.ORDER_SUBMIT;
        record.sequence = sequence;
        record.timestamp = System.currentTimeMillis();
        record.orderId = order.getOrderId();
        record.clientOrderId = order.getClientOrderId();
        record.symbol = order.getSymbol();
        record.side = order.getSide();
        record.orderType = order.getType();
        record.price = order.getPrice();
        record.quantity = order.getQuantity();
        record.userId = order.getUserId();
        return record;
    }

    /**
     * 创建订单取消记录
     */
    public static WalRecord createOrderCancel(String orderId, String symbol, long sequence) {
        WalRecord record = new WalRecord();
        record.type = WalType.ORDER_CANCEL;
        record.sequence = sequence;
        record.timestamp = System.currentTimeMillis();
        record.orderId = orderId;
        record.symbol = symbol;
        return record;
    }

    /**
     * 创建成交记录
     */
    public static WalRecord createTrade(Trade trade, long sequence) {
        WalRecord record = new WalRecord();
        record.type = WalType.TRADE;
        record.sequence = sequence;
        record.timestamp = System.currentTimeMillis();
        record.symbol = trade.getSymbol();
        record.buyOrderId = trade.getBuyOrderId();
        record.sellOrderId = trade.getSellOrderId();
        record.tradePrice = trade.getPrice();
        record.tradeQuantity = trade.getQuantity();
        record.side = trade.getSide();
        return record;
    }

    /**
     * 创建快照标记
     */
    public static WalRecord createSnapshotMarker(long sequence) {
        WalRecord record = new WalRecord();
        record.type = WalType.SNAPSHOT;
        record.sequence = sequence;
        record.timestamp = System.currentTimeMillis();
        return record;
    }

    /**
     * 序列化为字符串（格式：TYPE|timestamp|...fields...）
     */
    public String serialize() {
        switch (type) {
            case ORDER_SUBMIT:
                return String.format("ORDER_SUBMIT|%d|%s|%s|%s|%s|%s|%s|%s|%s",
                        timestamp, orderId, clientOrderId, symbol, side,
                        orderType, price, quantity, userId);
            case ORDER_CANCEL:
                return String.format("ORDER_CANCEL|%d|%s|%s",
                        timestamp, orderId, symbol);
            case TRADE:
                return String.format("TRADE|%d|%s|%s|%s|%s|%s",
                        timestamp, symbol, buyOrderId, sellOrderId, tradePrice, tradeQuantity);
            case SNAPSHOT:
                return String.format("SNAPSHOT|%d", timestamp);
            default:
                return "";
        }
    }

    /**
     * 从字符串反序列化
     */
    public static WalRecord deserialize(String line) {
        if (line == null || line.isEmpty()) {
            return null;
        }

        String[] parts = line.split("\\|");
        if (parts.length == 0) {
            return null;
        }

        String typeStr = parts[0];
        WalType type;

        try {
            type = WalType.valueOf(typeStr);
        } catch (IllegalArgumentException e) {
            return null;
        }

        WalRecord record = new WalRecord();
        record.type = type;

        try {
            switch (type) {
                case ORDER_SUBMIT:
                    if (parts.length >= 10) {
                        record.timestamp = Long.parseLong(parts[1]);
                        record.orderId = parts[2];
                        record.clientOrderId = parts[3].isEmpty() ? null : parts[3];
                        record.symbol = parts[4];
                        record.side = Side.valueOf(parts[5]);
                        record.orderType = OrderType.valueOf(parts[6]);
                        record.price = new BigDecimal(parts[7]);
                        record.quantity = new BigDecimal(parts[8]);
                        record.userId = parts[9];
                    }
                    break;
                case ORDER_CANCEL:
                    if (parts.length >= 4) {
                        record.timestamp = Long.parseLong(parts[1]);
                        record.orderId = parts[2];
                        record.symbol = parts[3];
                    }
                    break;
                case TRADE:
                    if (parts.length >= 7) {
                        record.timestamp = Long.parseLong(parts[1]);
                        record.symbol = parts[2];
                        record.buyOrderId = parts[3];
                        record.sellOrderId = parts[4];
                        record.tradePrice = new BigDecimal(parts[5]);
                        record.tradeQuantity = new BigDecimal(parts[6]);
                    }
                    break;
                case SNAPSHOT:
                    if (parts.length >= 2) {
                        record.timestamp = Long.parseLong(parts[1]);
                    }
                    break;
            }
        } catch (Exception e) {
            // 解析失败返回 null
            return null;
        }

        return record;
    }

    /**
     * 将记录转换为 Order 对象（用于重放）
     */
    public Order toOrder() {
        if (type != WalType.ORDER_SUBMIT) {
            return null;
        }

        Order order = new Order();
        order.setOrderId(orderId);
        order.setClientOrderId(clientOrderId);
        order.setSymbol(symbol);
        order.setSide(side);
        order.setType(orderType);
        order.setPrice(price);
        order.setQuantity(quantity);
        order.setUserId(userId);
        return order;
    }
}
