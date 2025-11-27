package com.matching.wal;

import com.matching.disruptor.OrderEvent;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;

public class WalWriter {
    private final String walFile;
    private RandomAccessFile raf;

    public WalWriter(String walFile) {
        this.walFile = walFile;
        try {
            Files.createDirectories(Paths.get(walFile).getParent());
            raf = new RandomAccessFile(walFile, "rw");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void append(OrderEvent event) {
        try {
            // 序列化写入（简化，用 JSON 或自定义）
            String entry = event.getOrder().getOrderId() + "|" + event.getAction() + "\n";
            raf.write(entry.getBytes());
            raf.getFD().sync(); // 强制落盘
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // 重放：启动时读取 wal 文件恢复状态（简化实现）
    public void replay() throws IOException {
        // 读取文件，重放事件到 Shard
        try (BufferedReader br = new BufferedReader(new FileReader(walFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                // 解析 line，重放 OrderEvent
                System.out.println("Replay: " + line);
            }
        }
    }
}