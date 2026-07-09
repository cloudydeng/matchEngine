package com.matching.disruptor;

import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;


import lombok.extern.slf4j.Slf4j;

@Slf4j
public class OrderEventProducer {
    private final Disruptor<OrderEvent>[] disruptors;

    @SuppressWarnings("unchecked")
    public OrderEventProducer(Disruptor<OrderEvent>[] disruptors) {
        this.disruptors = disruptors;

    }

    public void publish(OrderEvent event) {

        String symbol = event.getOrder().getSymbol();
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol is required");
        }
        int shardId = Math.floorMod(symbol.hashCode(), disruptors.length);
        RingBuffer<OrderEvent> ringBuffer = disruptors[shardId].getRingBuffer();
        long sequence = ringBuffer.next();
        try {
            ringBuffer.get(sequence).setOrder(event.getOrder());
            ringBuffer.get(sequence).setAction(event.getAction());
        } finally {
            ringBuffer.publish(sequence);
        }
    }
}
