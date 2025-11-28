package com.matching.wal;

import com.matching.disruptor.OrderEvent;
import lombok.Data;

import java.io.Serializable;

@Data
public class WalEntry implements Serializable {
    private OrderEvent event;
    private long sequence;

    public WalEntry(OrderEvent event, long sequence) {
        this.event = event;
        this.sequence = sequence;
    }

}