// 新增文件：OrderBookPersistence.java
package com.matching.core.persistence;

import com.matching.core.domain.*;
import com.matching.core.engine.L3OrderBook;

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

public class OrderBookPersistence {
    private static final int SNAPSHOT_INTERVAL_SECONDS = 10;  // 每10秒一次快照
    private static final String SNAPSHOT_DIR = "snapshots/";
    private static final String WAL_DIR = "wal/";

    private final L3OrderBook orderBook;
    private final String symbol;
    private final FileChannel snapshotChannel;
    private final MappedByteBuffer snapshotBuffer;
    private final RandomAccessFile walFile;
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

        // 2. 初始化 WAL 文件（追加模式）
        walFile = new RandomAccessFile(WAL_DIR + symbol + ".wal", "rw");
        long length = walFile.length();
        if (length > 0) {
            walFile.seek(length);
        }

        // 3. 启动定时快照任务
        scheduler.scheduleAtFixedRate(this::takeSnapshot,
                SNAPSHOT_INTERVAL_SECONDS, SNAPSHOT_INTERVAL_SECONDS, TimeUnit.SECONDS);

        // 4. 启动时尝试恢复（如果有快照）
        recoverIfPossible();
    }

    // 每10秒做一次全量快照
    private void takeSnapshot() {
        try {
            snapshotBuffer.rewind();
            snapshotBuffer.putInt(1); // version
            snapshotBuffer.putLong(Instant.now().toEpochMilli()); // timestamp

            // 写入 bids
            var bids = orderBook.getClass()
                    .getDeclaredField("bids")
                    .getDeclaredAnnotation(null); // 通过反射获取私有字段（实际用 Unsafe 或封装方法）
            // 下面用封装方式（推荐！在 L3OrderBook 加两个 getter）

            // 为了演示，先留空，实际你需要暴露 bids/asks 的 snapshot 方法
            writeBookSnapshot(snapshotBuffer, orderBook.getBidsForSnapshot());
            writeBookSnapshot(snapshotBuffer, orderBook.getAsksForSnapshot());

            snapshotBuffer.putLong(0xDEADBEEF); // EOF marker
            snapshotBuffer.force(); // 强制刷盘

            // 快照成功后，截断 WAL（可选，节省磁盘）
            truncateWalAfterSnapshot();

            System.out.println("[" + symbol + "] Snapshot saved at " + Instant.now());
        } catch (Exception e) {
            System.err.println("Snapshot failed for " + symbol + ": " + e);
            e.printStackTrace();
        }
    }

    private void writeBookSnapshot(MappedByteBuffer buf, List<Map.Entry<BigDecimal, BigDecimal>> levels) {
        buf.putInt(levels.size());
        for (var e : levels) {
            writeString(buf, e.getKey().toPlainString());
            writeString(buf, e.getValue().toPlainString());
        }
    }

    private void writeString(MappedByteBuffer buf, String s) {
        byte[] bytes = s.getBytes();
        buf.putInt(bytes.length);
        buf.put(bytes);
    }

    // WAL 追加（每笔订单、撤单、成交都必须写）
    public void appendWal(String logLine) {
        try {
            walFile.write((logLine + "\n").getBytes());
            // 不 force 太频繁，依赖系统 crash-safe
        } catch (Exception e) {
            System.err.println("WAL write failed: " + e);
        }
    }

    private void truncateWalAfterSnapshot() throws IOException {
        walFile.setLength(0); // 简单粗暴截断（生产可用 log rotate）
        walFile.seek(0);
    }

    private void recoverIfPossible() throws IOException {
        // 1. 先加载最新快照
        if (snapshotChannel.size() > 0) {
            snapshotBuffer.rewind();
            int version = snapshotBuffer.getInt();
            if (version == 1) {
                long ts = snapshotBuffer.getLong();
                System.out.println("[" + symbol + "] Loading snapshot from " + Instant.ofEpochMilli(ts));
                // 恢复 bids/asks（需要 L3OrderBook 提供 restore 方法）
            }
        }

        // 2. 再重放 WAL（从快照时间点之后）
        replayWal();
    }

    private void replayWal() throws IOException {
        // 简化实现，实际要从文件逐行解析 ORDER/CANCEL/TRADE
        System.out.println("[" + symbol + "] WAL replay skipped in demo");
    }

    public void shutdown() {
        scheduler.shutdownNow();
        try {
            snapshotChannel.close();
            walFile.close();
        } catch (Exception ignored) {}
    }
}