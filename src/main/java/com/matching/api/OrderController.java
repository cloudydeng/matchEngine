package com.matching.api;

import com.matching.api.dto.CancelRequest;
import com.matching.api.dto.OrderRequest;
import com.matching.core.domain.Order;
import com.matching.core.domain.OrderType;
import com.matching.disruptor.OrderEvent;
import com.matching.disruptor.OrderEventProducer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@Slf4j
public class OrderController {

    @Autowired
    private OrderEventProducer producer;

    @PostMapping("/order")
    public ResponseEntity<String> submitOrder(@RequestBody OrderRequest req) {
        String validationError = validateSubmit(req);
        if (validationError != null) {
            return ResponseEntity.badRequest().body(validationError);
        }

        Order order = new Order();
        order.setSymbol(req.getSymbol());
        order.setSide(req.getSide());
        order.setType(req.getType());
        order.setPrice(req.getPrice());
        order.setQuantity(req.getQuantity());
        order.setOrderId(req.getOrderId());
        order.setClientOrderId(req.getClientOrderId());
        order.setUserId(req.getUserId());

        OrderEvent event = new OrderEvent();
        event.setOrder(order);
        event.setAction("SUBMIT");
        producer.publish(event);

        log.info("Order submitted: symbol={}, clientOrderId={}, orderId={}",
                req.getSymbol(), req.getClientOrderId(), order.getOrderId());

        return ResponseEntity.ok("Order submitted: " + order.getOrderId());
    }

    @PostMapping("/cancel")
    public ResponseEntity<String> cancelOrder(@RequestBody CancelRequest req) {
        String validationError = validateCancel(req);
        if (validationError != null) {
            return ResponseEntity.badRequest().body(validationError);
        }

        Order dummyOrder = new Order();
        dummyOrder.setSymbol(req.getSymbol());
        dummyOrder.setOrderId(req.getOrderId());
        dummyOrder.setClientOrderId(req.getClientOrderId());

        OrderEvent event = new OrderEvent();
        event.setOrder(dummyOrder);
        event.setAction("CANCEL");
        producer.publish(event);

        String identifier = !isBlank(req.getClientOrderId()) ? req.getClientOrderId() : req.getOrderId();
        log.info("Cancel submitted: symbol={}, identifier={}", req.getSymbol(), identifier);

        return ResponseEntity.ok("Cancel submitted: " + identifier);
    }

    private String validateSubmit(OrderRequest req) {
        if (req == null) {
            return "request body is required";
        }
        if (isBlank(req.getSymbol())) {
            return "symbol is required";
        }
        if (req.getSide() == null) {
            return "side is required";
        }
        if (req.getType() == null) {
            return "type is required";
        }
        if (req.getQuantity() == null || req.getQuantity().signum() <= 0) {
            return "quantity must be positive";
        }
        if (req.getType() == OrderType.LIMIT
                && (req.getPrice() == null || req.getPrice().signum() <= 0)) {
            return "price must be positive for LIMIT orders";
        }
        if (req.getType() != OrderType.LIMIT && req.getType() != OrderType.MARKET) {
            return "only LIMIT and MARKET orders are supported";
        }
        return null;
    }

    private String validateCancel(CancelRequest req) {
        if (req == null) {
            return "request body is required";
        }
        if (isBlank(req.getSymbol())) {
            return "symbol is required";
        }
        if (isBlank(req.getOrderId()) && isBlank(req.getClientOrderId())) {
            return "orderId or clientOrderId is required";
        }
        return null;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
