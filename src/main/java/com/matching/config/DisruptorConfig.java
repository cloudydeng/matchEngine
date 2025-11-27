package com.matching.config;


import com.lmax.disruptor.BusySpinWaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.matching.disruptor.OrderEvent;
import com.matching.disruptor.OrderEventHandler;
import com.matching.disruptor.OrderEventProducer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

@Configuration
@Slf4j
public class DisruptorConfig {

    @Value("${app.shard-count:32}")
    private int shardCount;

    @Value("${app.disruptor-buffer-size:131072}")
    private int bufferSize;

    @Value("${app.wal-dir:./wal/}")
    private String walDir;




    @Bean
    public Disruptor<OrderEvent>[] disruptors() {
        @SuppressWarnings("unchecked")
        Disruptor<OrderEvent>[] disruptors = new Disruptor[shardCount];

        for (int i = 0; i < shardCount; i++) {
            int shardId = i;
            ThreadFactory threadFactory = Executors.defaultThreadFactory();
            Disruptor<OrderEvent> disruptor = new Disruptor<>(
                    OrderEvent.EVENT_FACTORY,           // 静态 EventFactory
                    bufferSize,
                    threadFactory,
                    ProducerType.SINGLE,
                    new BusySpinWaitStrategy()
            );

            disruptor.handleEventsWith(new OrderEventHandler());
            disruptor.start();

            disruptors[i] = disruptor;
        }
        return disruptors;
    }




    // 生产者正常依赖
    @Bean
    public OrderEventProducer orderEventProducer(Disruptor<OrderEvent>[] disruptors) {
        return new OrderEventProducer(disruptors);
    }



}