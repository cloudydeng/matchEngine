package com.matching.api;

import com.matching.api.dto.CancelRequest;
import com.matching.api.dto.OrderRequest;
import com.matching.core.domain.Order;
import com.matching.disruptor.OrderEvent;
import com.matching.disruptor.OrderEventProducer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 订单控制器
 * 提供订单提交和取消的 REST API
 */
@RestController
@RequestMapping("/api")
@Slf4j
public class OrderController {

    @Autowired
    private OrderEventProducer producer;

    /**
     * 提交订单
     *
     * 请求示例:
     * POST /api/order
     * {
     *   "symbol": "BTCUSDT",
     *   "side": "BUY",
     *   "type": "LIMIT",
     *   "price": "50000.00",
     *   "quantity": "0.1",
     *   "clientOrderId": "my-order-123",  // 可选
     *   "userId": "user123"              // 可选
     * }
     */
    @PostMapping("/order")
    public ResponseEntity<String> submitOrder(@RequestBody OrderRequest req) {
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

    /**
     * 取消订单
     *
     * 支持两种方式：
     * 1. 使用 orderId 取消
     * 2. 使用 clientOrderId 取消
     *
     * 请求示例1:
     * POST /api/cancel
     * {
     *   "symbol": "BTCUSDT",
     *   "orderId": "BTCUSDT_1234567890"
     * }
     *
     * 请求示例2:
     * POST /api/cancel
     * {
     *   "symbol": "BTCUSDT",
     *   "clientOrderId": "my-order-123"
     * }
     */
    @PostMapping("/cancel")
    public ResponseEntity<String> cancelOrder(@RequestBody CancelRequest req) {
        Order dummyOrder = new Order();
        dummyOrder.setSymbol(req.getSymbol());
        dummyOrder.setOrderId(req.getOrderId());
        dummyOrder.setClientOrderId(req.getClientOrderId());

        OrderEvent event = new OrderEvent();
        event.setOrder(dummyOrder);
        event.setAction("CANCEL");

        producer.publish(event);

        String identifier = req.getClientOrderId() != null ? req.getClientOrderId() : req.getOrderId();
        log.info("Cancel submitted: symbol={}, identifier={}", req.getSymbol(), identifier);

        return ResponseEntity.ok("Cancel submitted: " + identifier);
    }
}
