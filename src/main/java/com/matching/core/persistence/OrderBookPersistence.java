package com.matching.core.persistence;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.matching.core.domain.Order;
import com.matching.core.domain.Trade;
import com.matching.core.engine.L3OrderBook;
import com.matching.wal.WalRecord;
import com.matching.wal.WalWriter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
public class OrderBookPersistence {
    private static final int SNAPSHOT_INTERVAL_SECONDS = 10;
    private static final String SNAPSHOT_DIR = "snapshots";
    private static final String WAL_DIR = "wal";

    private final L3OrderBook orderBook;
    private final String symbol;
    private final Path snapshotPath;
    private final WalWriter walWriter;
    private final ObjectMapper mapper;
    private final ScheduledExecutorService scheduler;

    public OrderBookPersistence(L3OrderBook orderBook, String symbol) throws IOException {
        this(orderBook, symbol, Paths.get(SNAPSHOT_DIR), Paths.get(WAL_DIR));
    }

    public OrderBookPersistence(L3OrderBook orderBook, String symbol, Path snapshotDir, Path walDir) throws IOException {
        this.orderBook = orderBook;
        this.symbol = symbol;
        this.mapper = new ObjectMapper()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "orderbook-snapshot-" + symbol);
            t.setDaemon(true);
            return t;
        });

        Files.createDirectories(snapshotDir);
        Files.createDirectories(walDir);
        this.snapshotPath = snapshotDir.resolve(symbol + ".snapshot.json");
        this.walWriter = new WalWriter(walDir.resolve(symbol + ".wal").toString());

        recoverIfPossible();

        scheduler.scheduleAtFixedRate(this::takeSnapshot,
                SNAPSHOT_INTERVAL_SECONDS, SNAPSHOT_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    public void appendSubmit(Order order) {
        appendOrderSubmit(order);
    }

    public void appendCancel(String orderId) {
        appendOrderCancel(orderId);
    }

    public void appendOrderSubmit(Order order) {
        walWriter.appendOrderSubmit(WalRecord.createOrderSubmit(order, 0));
    }

    public void appendOrderCancel(String orderId) {
        walWriter.appendOrderCancel(WalRecord.createOrderCancel(orderId, symbol, 0));
    }

    public void appendTrade(Trade trade) {
        walWriter.appendTrade(WalRecord.createTrade(trade, 0));
    }

    public void forceSnapshot() {
        takeSnapshot();
    }

    private void takeSnapshot() {
        try {
            Snapshot snapshot = Snapshot.from(symbol, orderBook.getOrdersForSnapshot());
            Path tmp = snapshotPath.resolveSibling(snapshotPath.getFileName() + ".tmp");
            Files.writeString(tmp, mapper.writeValueAsString(snapshot), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, snapshotPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(tmp, snapshotPath, StandardCopyOption.REPLACE_EXISTING);
            }
            walWriter.truncate();
            log.info("[{}] snapshot saved with {} open orders", symbol, snapshot.orders.size());
        } catch (Exception e) {
            log.error("[{}] snapshot failed", symbol, e);
        }
    }

    private void recoverIfPossible() {
        loadSnapshot();
        replayWalIncremental();
    }

    private void loadSnapshot() {
        if (!Files.exists(snapshotPath)) {
            return;
        }
        try {
            Snapshot snapshot = mapper.readValue(snapshotPath.toFile(), Snapshot.class);
            if (snapshot.version == 1 && symbol.equals(snapshot.symbol)) {
                orderBook.restoreFromSnapshotOrders(snapshot.toSnapshotOrders());
                log.info("[{}] loaded snapshot from {} with {} open orders",
                        symbol, Instant.ofEpochMilli(snapshot.timestamp), snapshot.orders.size());
            }
        } catch (Exception e) {
            log.warn("[{}] ignored unreadable snapshot {}", symbol, snapshotPath, e);
        }
    }

    private void replayWalIncremental() {
        List<WalRecord> records = walWriter.readAllRecords();
        int replayed = 0;

        for (WalRecord record : records) {
            try {
                switch (record.getType()) {
                    case ORDER_SUBMIT:
                        Order order = record.toOrder();
                        if (order != null) {
                            orderBook.replayOrder(order);
                            replayed++;
                        }
                        break;
                    case ORDER_CANCEL:
                        orderBook.replayCancel(record.getOrderId());
                        replayed++;
                        break;
                    case TRADE:
                    case SNAPSHOT:
                        break;
                }
            } catch (Exception e) {
                log.error("Failed to replay WAL record: {}", record, e);
            }
        }

        if (!records.isEmpty()) {
            log.info("[{}] replayed {} WAL records", symbol, replayed);
        }
    }

    public void shutdown() {
        scheduler.shutdownNow();
        try {
            walWriter.close();
        } catch (Exception ignored) {
        }
    }

    public static class Snapshot {
        public int version;
        public String symbol;
        public long timestamp;
        public List<SnapshotOrderDto> orders = new ArrayList<>();

        public Snapshot() {
        }

        static Snapshot from(String symbol, List<L3OrderBook.SnapshotOrder> orders) {
            Snapshot snapshot = new Snapshot();
            snapshot.version = 1;
            snapshot.symbol = symbol;
            snapshot.timestamp = System.currentTimeMillis();
            if (orders != null) {
                for (L3OrderBook.SnapshotOrder order : orders) {
                    snapshot.orders.add(SnapshotOrderDto.from(order));
                }
            }
            return snapshot;
        }

        List<L3OrderBook.SnapshotOrder> toSnapshotOrders() {
            List<L3OrderBook.SnapshotOrder> result = new ArrayList<>();
            if (orders == null) {
                return result;
            }
            for (SnapshotOrderDto order : orders) {
                result.add(order.toSnapshotOrder());
            }
            return result;
        }
    }

    public static class SnapshotOrderDto {
        public String orderId;
        public String clientOrderId;
        public String userId;
        public String side;
        public String type;
        public BigDecimal price;
        public BigDecimal remain;
        public long timestamp;

        public SnapshotOrderDto() {
        }

        static SnapshotOrderDto from(L3OrderBook.SnapshotOrder order) {
            SnapshotOrderDto dto = new SnapshotOrderDto();
            dto.orderId = order.orderId;
            dto.clientOrderId = order.clientOrderId;
            dto.userId = order.userId;
            dto.side = order.side != null ? order.side.name() : null;
            dto.type = order.type != null ? order.type.name() : null;
            dto.price = order.price;
            dto.remain = order.remain;
            dto.timestamp = order.timestamp;
            return dto;
        }

        L3OrderBook.SnapshotOrder toSnapshotOrder() {
            return new L3OrderBook.SnapshotOrder(
                    orderId,
                    clientOrderId,
                    userId,
                    side != null ? com.matching.core.domain.Side.valueOf(side) : null,
                    type != null ? com.matching.core.domain.OrderType.valueOf(type) : null,
                    price,
                    remain,
                    timestamp
            );
        }
    }
}
