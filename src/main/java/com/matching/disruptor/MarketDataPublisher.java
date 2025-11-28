package com.matching.disruptor;

import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.matching.core.domain.DepthLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@RequiredArgsConstructor
@Slf4j
public class MarketDataPublisher {
    private final Disruptor<MarketDataEvent> marketDisruptor;

    public void publishUpdate(String symbol, BigDecimal price, BigDecimal qty, boolean isBid) {
        RingBuffer<MarketDataEvent> rb = marketDisruptor.getRingBuffer();
        long seq = rb.next();
        try {
            MarketDataEvent e = rb.get(seq);
            e.symbol = symbol;
            e.sequence = seq;
            e.timestamp = System.currentTimeMillis();
            e.bids.clear();
            e.asks.clear();
            if (isBid) {
                e.bids.add(new DepthLevel(price, qty));
            } else {
                e.asks.add(new DepthLevel(price, qty));
            }
        } finally {
            rb.publish(seq);
        }
    }
}