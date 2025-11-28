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

            String entry = event.getOrder().getOrderId() + "|" + event.getAction() + "\n";
            raf.write(entry.getBytes());
            raf.getFD().sync();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    public void replay() throws IOException {

        try (BufferedReader br = new BufferedReader(new FileReader(walFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                System.out.println("Replay: " + line);
            }
        }
    }
}