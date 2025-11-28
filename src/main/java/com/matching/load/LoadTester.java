package com.matching.load;

import com.matching.api.OrderController;
import com.matching.api.dto.OrderRequest;
import com.matching.core.domain.OrderType;
import com.matching.core.domain.Side;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

@Component
@Slf4j
public class LoadTester implements ApplicationRunner {

    private final OrderController orderController;
    private final AtomicLong successCount = new AtomicLong();
    private final AtomicLong failCount = new AtomicLong();

    public LoadTester(OrderController orderController) {
        this.orderController = orderController;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        log.info("LoadTester  100M...");

        long totalOrders = 1000000000L;
        int threads = Runtime.getRuntime().availableProcessors() * 4;

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        long perThread = totalOrders / threads;
        long start = System.nanoTime();

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                for (long j = 0; j < perThread; j++) {
                    try {
                        sendRandomOrder();
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        failCount.incrementAndGet();
                    }
                }
            });
        }


        for (long j = 0; j < totalOrders % threads; j++) {
            sendRandomOrder();
            successCount.incrementAndGet();
        }

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.MINUTES);

        double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
        long success = successCount.get();
        log.info("%n done！%n");

    }

    private void sendRandomOrder() {
        OrderRequest req = new OrderRequest();
        req.setOrderId("load_" + ThreadLocalRandom.current().nextLong(1_000_000_000L));
        req.setSymbol(ThreadLocalRandom.current().nextBoolean() ? "BTCUSDT" : "ETHUSDT");
        req.setSide(Side.valueOf(ThreadLocalRandom.current().nextBoolean() ? "BUY" : "SELL"));
        req.setType(ThreadLocalRandom.current().nextBoolean() ? OrderType.LIMIT : OrderType.MARKET);
        if (!req.getType().equals(OrderType.MARKET)) {
            req.setPrice(new BigDecimal(1000));
        }
        req.setQuantity(BigDecimal.valueOf(0.001 + ThreadLocalRandom.current().nextDouble() * 0.2));
        orderController.submitOrder(req);
    }
}