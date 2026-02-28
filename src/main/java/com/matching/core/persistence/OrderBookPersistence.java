// 新增文件：OrderBookPersistence.java
package com.matching.core.persistence;

import com.matching.core.domain.*;
import com.matching.core.engine.L3OrderBook;
import com.matching.wal.WalRecord;
import com.matching.wal.WalWriter;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.math.BigDecimal;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 订单簿持久化管理
 * 使用快照 + WAL (Write-Ahead Log) 实现崩溃恢复
 */
@Slf4j
public class OrderBookPersistence {
    private static final int SNAPSHOT_INTERVAL_SECONDS = 10;  // 每10秒一次快照
    private static final String SNAPSHOT_DIR = "snapshots/";
    private static final String WAL_DIR = "wal/";

    private final L3OrderBook orderBook;
    private final String symbol;
    private final FileChannel snapshotChannel;
    private final MappedByteBuffer snapshotBuffer;
    private final WalWriter walWriter;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    static {
        try {
            Files.createDirectories(Paths.get(SNAPSHOT_DIR));
            Files.createDirectories(Paths.get(WAL_DIR));
        } catch (Exception e) {
            throw new RuntimeException("Failed to create persistence dirs", e);
        }
    }

    public OrderBookPersistence(L3OrderBook orderBook, String symbol) throws IOException {
        this.orderBook = orderBook;
        this.symbol = symbol;

        // 1. 初始化快照文件（100MB 足够支持 10万+ 档位）
        Path snapshotPath = Paths.get(SNAPSHOT_DIR + symbol + ".snapshot");
        snapshotChannel = FileChannel.open(snapshotPath,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        snapshotBuffer = snapshotChannel.map(FileChannel.MapMode.READ_WRITE, 0, 100L * 1024 * 1024);

        // 2. 初始化 WAL 文件
        walWriter = new WalWriter(WAL_DIR + symbol + ".wal");

        // 3. 启动时尝试恢复
        recoverIfPossible();

        // 4. 启动定时快照任务
        scheduler.scheduleAtFixedRate(this::takeSnapshot,
                SNAPSHOT_INTERVAL_SECONDS, SNAPSHOT_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * 写入订单提交 WAL
     */
    public void appendOrderSubmit(Order order) {
        WalRecord record = WalRecord.createOrderSubmit(order, 0);
        walWriter.appendOrderSubmit(record);
    }

    /**
     * 写入订单取消 WAL
     */
    public void appendOrderCancel(String orderId) {
        WalRecord record = WalRecord.createOrderCancel(orderId, symbol, 0);
        walWriter.appendOrderCancel(record);
    }

    /**
     * 写入成交 WAL
     */
    public void appendTrade(Trade trade) {
        WalRecord record = WalRecord.createTrade(trade, 0);
        walWriter.appendTrade(record);
    }

    /**
     * 每10秒做一次全量快照
     */
    private void takeSnapshot() {
        try {
            snapshotBuffer.rewind();
            snapshotBuffer.putInt(2); // version
            snapshotBuffer.putLong(Instant.now().toEpochMilli()); // timestamp

            // 写入订单级快照，保留真实 orderId/clientOrderId
            writeOrderSnapshot(snapshotBuffer, orderBook.getOrdersForSnapshot());

            snapshotBuffer.putLong(0xDEADBEEF); // EOF marker
            snapshotBuffer.force(); // 强制刷盘

            // 快照成功后，截断 WAL
            truncateWalAfterSnapshot();

            log.info("[{}] Snapshot saved at {}", symbol, Instant.now());
        } catch (Exception e) {
            log.error("Snapshot failed for {}: {}", symbol, e.getMessage(), e);
        }
    }

    private void writeBookSnapshot(MappedByteBuffer buf, List<Map.Entry<BigDecimal, BigDecimal>> levels) {
        buf.putInt(levels.size());
        for (var e : levels) {
            writeString(buf, e.getKey().toPlainString());
            writeString(buf, e.getValue().toPlainString());
        }
    }

    private void writeOrderSnapshot(MappedByteBuffer buf, List<L3OrderBook.SnapshotOrder> orders) {
        buf.putInt(orders.size());
        for (L3OrderBook.SnapshotOrder so : orders) {
            writeNullableString(buf, so.orderId);
            writeNullableString(buf, so.clientOrderId);
            writeNullableString(buf, so.userId);
            writeNullableString(buf, so.side != null ? so.side.name() : null);
            writeNullableString(buf, so.type != null ? so.type.name() : null);
            writeNullableString(buf, so.price != null ? so.price.toPlainString() : null);
            writeNullableString(buf, so.remain != null ? so.remain.toPlainString() : null);
            buf.putLong(so.timestamp);
        }
    }

    private void writeString(MappedByteBuffer buf, String s) {
        byte[] bytes = s.getBytes();
        buf.putInt(bytes.length);
        buf.put(bytes);
    }

    private void writeNullableString(MappedByteBuffer buf, String s) {
        if (s == null) {
            buf.putInt(-1);
            return;
        }
        writeString(buf, s);
    }

    /**
     * 读取快照中的深度数据
     */
    private SnapshotData loadSnapshot() throws IOException {
        SnapshotData data = new SnapshotData();

        if (snapshotChannel.size() == 0) {
            return data; // 空快照
        }

        snapshotBuffer.rewind();
        int version = snapshotBuffer.getInt();
        if (version != 1 && version != 2) {
            log.warn("Unknown snapshot version: {}", version);
            return data;
        }

        long ts = snapshotBuffer.getLong();
        data.timestamp = Instant.ofEpochMilli(ts);
        log.info("[{}] Loading snapshot from {}", symbol, data.timestamp);

        if (version == 1) {
            // 旧版：仅读深度聚合
            int bidCount = snapshotBuffer.getInt();
            for (int i = 0; i < bidCount; i++) {
                String priceStr = readString(snapshotBuffer);
                String qtyStr = readString(snapshotBuffer);
                data.bids.add(Map.entry(new BigDecimal(priceStr), new BigDecimal(qtyStr)));
            }

            int askCount = snapshotBuffer.getInt();
            for (int i = 0; i < askCount; i++) {
                String priceStr = readString(snapshotBuffer);
                String qtyStr = readString(snapshotBuffer);
                data.asks.add(Map.entry(new BigDecimal(priceStr), new BigDecimal(qtyStr)));
            }
        } else {
            // 新版：订单级快照
            int orderCount = snapshotBuffer.getInt();
            for (int i = 0; i < orderCount; i++) {
                String orderId = readNullableString(snapshotBuffer);
                String clientOrderId = readNullableString(snapshotBuffer);
                String userId = readNullableString(snapshotBuffer);
                String sideName = readNullableString(snapshotBuffer);
                String typeName = readNullableString(snapshotBuffer);
                String priceStr = readNullableString(snapshotBuffer);
                String remainStr = readNullableString(snapshotBuffer);
                long ts = snapshotBuffer.getLong();

                if (orderId == null || sideName == null || priceStr == null || remainStr == null) {
                    continue;
                }
                data.orders.add(new L3OrderBook.SnapshotOrder(
                        orderId,
                        clientOrderId,
                        userId,
                        Side.valueOf(sideName),
                        typeName != null ? OrderType.valueOf(typeName) : OrderType.LIMIT,
                        new BigDecimal(priceStr),
                        new BigDecimal(remainStr),
                        ts
                ));
            }
        }

        // 验证 EOF marker
        long eof = snapshotBuffer.getLong();
        if (eof != 0xDEADBEEF) {
            log.warn("Invalid EOF marker in snapshot");
        }

        return data;
    }

    private String readString(MappedByteBuffer buf) throws IOException {
        int len = buf.getInt();
        byte[] bytes = new byte[len];
        buf.get(bytes);
        return new String(bytes);
    }

    private String readNullableString(MappedByteBuffer buf) throws IOException {
        int len = buf.getInt();
        if (len < 0) {
            return null;
        }
        byte[] bytes = new byte[len];
        buf.get(bytes);
        return new String(bytes);
    }

    private void truncateWalAfterSnapshot() throws IOException {
        walWriter.truncate();
    }

    /**
     * 恢复订单簿状态
     */
    private void recoverIfPossible() {
        try {
            // 1. 先加载最新快照
            SnapshotData snapshot = loadSnapshot();

            // 2. 用快照重建 L3 深度
            if (!snapshot.orders.isEmpty()) {
                orderBook.restoreFromSnapshotOrders(snapshot.orders);
            } else {
                // 兼容老快照格式
                orderBook.restoreFromSnapshot(snapshot.bids, snapshot.asks);
            }

            // 3. 再重放 WAL 增量
            replayWalIncremental();

            log.info("[{}] Recovery completed", symbol);
        } catch (Exception e) {
            log.error("Recovery failed for {}: {}", symbol, e.getMessage(), e);
        }
    }

    /**
     * 重放 WAL 中快照标记之后的记录
     */
    private void replayWalIncremental() {
        // 在当前截断策略下，WAL 仅保留快照之后的增量记录。
        List<WalRecord> records = walWriter.readAllRecords();

        for (WalRecord record : records) {
            try {
                switch (record.getType()) {
                    case ORDER_SUBMIT:
                        Order order = record.toOrder();
                        if (order != null) {
                            log.debug("Replay order: {}", order.getOrderId());
                            orderBook.replayOrder(order);
                        }
                        break;
                    case ORDER_CANCEL:
                        log.debug("Replay cancel: {}", record.getOrderId());
                        orderBook.replayCancel(record.getOrderId());
                        break;
                    case TRADE:
                        log.debug("Replay trade: {} @ {}",
                                record.getTradeQuantity(), record.getTradePrice());
                        // 由 submit/cancel 回放重建订单簿，TRADE 仅作为审计日志，不直接作用于簿状态。
                        break;
                    case SNAPSHOT:
                        // 忽略快照标记
                        break;
                }
            } catch (Exception e) {
                log.error("Failed to replay WAL record: {}", record, e);
            }
        }

        log.info("[{}] Replayed {} WAL records", symbol, records.size());
    }

    public void shutdown() {
        scheduler.shutdownNow();
        try {
            snapshotChannel.close();
            walWriter.close();
        } catch (Exception ignored) {}
    }

    /**
     * 快照数据结构
     */
    private static class SnapshotData {
        Instant timestamp;
        List<Map.Entry<BigDecimal, BigDecimal>> bids = new java.util.ArrayList<>();
        List<Map.Entry<BigDecimal, BigDecimal>> asks = new java.util.ArrayList<>();
        List<L3OrderBook.SnapshotOrder> orders = new java.util.ArrayList<>();
    }
}
