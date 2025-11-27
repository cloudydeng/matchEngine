package com.matching.api;

import com.fasterxml.jackson.core.JsonParser;
import com.matching.api.dto.CancelRequest;
import com.matching.api.dto.OrderRequest;
import com.matching.core.domain.Order;
import com.matching.core.domain.Side;
import com.matching.disruptor.OrderEvent;
import com.matching.disruptor.OrderEventProducer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api")
@Slf4j
public class OrderController {

    @Autowired
    private OrderEventProducer producer;

    @PostMapping("/order")
    public ResponseEntity<String> submitOrder(@RequestBody OrderRequest req) {
        Order order = new Order();
        order.setSymbol(req.getSymbol());
        order.setSide(req.getSide());
        order.setType(req.getType());
        order.setPrice(req.getPrice());
        order.setQuantity(req.getQuantity());
        order.setOrderId(req.getOrderId() != null ? req.getOrderId() : order.getOrderId());

        OrderEvent event = new OrderEvent();
        event.setOrder(order);
        event.setAction("SUBMIT");
        producer.publish(event);
        return ResponseEntity.ok("Order submitted: " + order.getOrderId());
    }

    @PostMapping("/cancel")
    public ResponseEntity<String> cancelOrder(@RequestBody CancelRequest req) {
        // 需查询 order 详情，这里简化
        Order dummyOrder = new Order();
        dummyOrder.setOrderId(req.getOrderId());

        OrderEvent event = new OrderEvent();
        event.setOrder(dummyOrder);
        event.setAction("CANCEL");

        producer.publish(event);
        return ResponseEntity.ok("Cancel submitted: " + req.getOrderId());
    }
}