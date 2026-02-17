package com.matching.wal;

import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * WAL (Write-Ahead Log) 写入和重放器
 * 使用标准化的 WalRecord 格式，支持崩溃恢复
 */
@Slf4j
public class WalWriter {
    private final String walFile;
    private final RandomAccessFile raf;
    private volatile long sequence = 0;

    public WalWriter(String walFile) {
        this.walFile = walFile;
        try {
            Files.createDirectories(Paths.get(walFile).getParent());
            raf = new RandomAccessFile(walFile, "rw");

            // 读取当前 sequence 值（从最后一行提取）
            long currentSeq = readLastSequence();
            this.sequence = currentSeq;

            log.info("WAL initialized: file={}, currentSequence={}", walFile, sequence);
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize WAL", e);
        }
    }

    /**
     * 写入订单提交记录
     */
    public void appendOrderSubmit(WalRecord record) {
        append(record);
    }

    /**
     * 写入订单取消记录
     */
    public void appendOrderCancel(WalRecord record) {
        append(record);
    }

    /**
     * 写入成交记录
     */
    public void appendTrade(WalRecord record) {
        append(record);
    }

    /**
     * 写入快照标记
     */
    public void appendSnapshotMarker() {
        WalRecord record = WalRecord.createSnapshotMarker(sequence);
        append(record);
    }

    /**
     * 通用写入方法
     */
    private void append(WalRecord record) {
        try {
            record.setSequence(++sequence);
            String entry = record.serialize() + "\n";
            raf.write(entry.getBytes());
            raf.getFD().sync();  // 强制刷盘，保证持久化

            log.debug("WAL append: type={}, sequence={}", record.getType(), record.getSequence());
        } catch (IOException e) {
            log.error("Failed to append WAL record", e);
            throw new RuntimeException("WAL append failed", e);
        }
    }

    /**
     * 读取 WAL 中的所有记录（用于重放）
     */
    public List<WalRecord> readAllRecords() {
        List<WalRecord> records = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(walFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                WalRecord record = WalRecord.deserialize(line);
                if (record != null) {
                    records.add(record);
                }
            }
            log.info("Read {} WAL records from {}", records.size(), walFile);
        } catch (IOException e) {
            log.error("Failed to read WAL records", e);
        }
        return records;
    }

    /**
     * 读取快照标记之后的所有记录（用于增量恢复）
     */
    public List<WalRecord> readRecordsAfterSnapshot() {
        List<WalRecord> records = new ArrayList<>();
        boolean afterSnapshot = false;

        try (BufferedReader br = new BufferedReader(new FileReader(walFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                WalRecord record = WalRecord.deserialize(line);
                if (record == null) {
                    continue;
                }

                if (record.getType() == WalRecord.WalType.SNAPSHOT) {
                    afterSnapshot = true;
                    log.info("Found snapshot marker at sequence={}", record.getSequence());
                    continue;
                }

                if (afterSnapshot) {
                    records.add(record);
                }
            }

            log.info("Read {} WAL records after snapshot marker", records.size());
        } catch (IOException e) {
            log.error("Failed to read WAL records after snapshot", e);
        }
        return records;
    }

    /**
     * 截断 WAL（快照完成后调用）
     */
    public void truncate() throws IOException {
        // 写入快照标记
        appendSnapshotMarker();

        // 重置文件
        raf.setLength(0);
        raf.seek(0);
        sequence = 0;
        log.info("WAL truncated: {}", walFile);
    }

    /**
     * 读取最后一行的 sequence 值
     */
    private long readLastSequence() {
        long lastSeq = 0;
        try (BufferedReader br = new BufferedReader(new FileReader(walFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                WalRecord record = WalRecord.deserialize(line);
                if (record != null) {
                    lastSeq = record.getSequence();
                }
            }
        } catch (IOException e) {
            log.warn("Failed to read last sequence, using 0");
        }
        return lastSeq;
    }

    /**
     * 关闭 WAL
     */
    public void close() throws IOException {
        if (raf != null) {
            raf.close();
        }
    }

    /**
     * 获取当前 sequence
     */
    public long getCurrentSequence() {
        return sequence;
    }
}
