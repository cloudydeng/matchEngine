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
        log.info("LoadTester 自动启动！开始狂轰 100 万订单...");

        long totalOrders = 1000000000L;      // ← 改这里控制订单数量
        int threads = Runtime.getRuntime().availableProcessors() * 4; // 用满 CPU

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

        // 最后一点尾巴订单
        for (long j = 0; j < totalOrders % threads; j++) {
            sendRandomOrder();
            successCount.incrementAndGet();
        }

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.MINUTES);

        double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
        long success = successCount.get();
        log.info("%n压测完成！%n");
        log.info("总订单：%,d   成功：%,d   失败：%,d%n", totalOrders, success, failCount.get());
        log.info("用时：%.2f 秒   QPS：%,.0f%n", seconds, success / seconds);
        log.info("LoadTester 执行完毕，服务继续运行中...（可继续下单）");
    }

    private void sendRandomOrder() {
        OrderRequest req = new OrderRequest();
        req.setOrderId("load_" + ThreadLocalRandom.current().nextLong(1_000_000_000L));
        req.setSymbol(ThreadLocalRandom.current().nextBoolean() ? "BTCUSDT" : "ETHUSDT");
        req.setSide(Side.valueOf(ThreadLocalRandom.current().nextBoolean() ? "BUY" : "SELL"));
        req.setType(ThreadLocalRandom.current().nextBoolean() ? OrderType.LIMIT : OrderType.MARKET);
        req.setPrice(new BigDecimal(1000));
        req.setQuantity(BigDecimal.valueOf(0.001 + ThreadLocalRandom.current().nextDouble() * 0.2));
        orderController.submitOrder(req);
    }
}