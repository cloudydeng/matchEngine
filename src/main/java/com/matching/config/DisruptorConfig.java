package com.matching.config;

import com.lmax.disruptor.BusySpinWaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.matching.account.FreezeService;
import com.matching.core.engine.MatchingEngineManager;
import com.matching.core.risk.RiskManager;
import com.matching.disruptor.OrderEvent;
import com.matching.disruptor.OrderEventHandler;
import com.matching.disruptor.OrderEventProducer;
import com.matching.market.KlineLogger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

@Configuration
@Slf4j
@RequiredArgsConstructor
public class DisruptorConfig {

    private final MatchingEngineManager matchingEngineManager;

    @Value("${app.shard-count:32}")
    private int shardCount;

    @Value("${app.disruptor-buffer-size:131072}")
    private int bufferSize;

    @Bean
    public Disruptor<OrderEvent>[] disruptors(RiskManager riskManager,
                                              Optional<FreezeService> freezeService,
                                              Optional<KlineLogger> klineLogger) {
        @SuppressWarnings("unchecked")
        Disruptor<OrderEvent>[] disruptors = new Disruptor[shardCount];

        for (int i = 0; i < shardCount; i++) {
            ThreadFactory threadFactory = Executors.defaultThreadFactory();
            Disruptor<OrderEvent> disruptor = new Disruptor<>(
                    OrderEvent.EVENT_FACTORY,
                    bufferSize,
                    threadFactory,
                    ProducerType.MULTI,
                    new BusySpinWaitStrategy()
            );

            disruptor.handleEventsWith(new OrderEventHandler(
                    matchingEngineManager,
                    riskManager,
                    freezeService.orElse(null),
                    klineLogger.orElse(null)
            ));
            disruptor.start();

            disruptors[i] = disruptor;
        }
        return disruptors;
    }

    @Bean
    public OrderEventProducer orderEventProducer(Disruptor<OrderEvent>[] disruptors) {
        return new OrderEventProducer(disruptors);
    }
}
