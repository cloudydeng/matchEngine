package com.matching.disruptor;

import com.lmax.disruptor.EventFactory;
import com.matching.core.domain.Order;
import lombok.Data;

@Data
public class OrderEvent {
    private Order order;
    private String action;


    public static final EventFactory<OrderEvent> EVENT_FACTORY = OrderEvent::new;
}