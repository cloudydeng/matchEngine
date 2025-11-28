package com.matching.config;

import com.lmax.disruptor.YieldingWaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.matching.api.MarketDataWebSocketHandler;
import com.matching.disruptor.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class MarketDataDisruptorConfig {

    private final MarketDataWebSocketHandler wsHandler;

    @Bean
    public Disruptor<MarketDataEvent> marketDataDisruptor() {
        Disruptor<MarketDataEvent> disruptor = new Disruptor<>(
                MarketDataEvent::new,
                131072,
                Thread.ofPlatform().name("market-data-", 0).factory(),
                ProducerType.MULTI,
                new YieldingWaitStrategy()
        );

        disruptor.handleEventsWith(new DepthBatcher(wsHandler));
        disruptor.start();
        return disruptor;
    }

    @Bean
    public MarketDataPublisher marketDataPublisher(Disruptor<MarketDataEvent> disruptor) {
        return new MarketDataPublisher(disruptor);
    }
}
