package com.matching.disruptor;

import com.lmax.disruptor.EventHandler;
import com.matching.core.domain.Trade;
import com.matching.core.engine.MatchingEngineManager;
import com.matching.core.index.ClientOrderIndex;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 订单事件处理器
 * 处理订单提交、取消，并记录到 WAL
 */
@Slf4j
public class OrderEventHandler implements EventHandler<OrderEvent> {

    // 全局 ClientOrderIndex 单例
    private static final ClientOrderIndex clientOrderIndex = new ClientOrderIndex();

    @Override
    public void onEvent(OrderEvent event, long sequence, boolean endOfBatch) throws Exception {
        var engine = MatchingEngineManager.getEngine(event.getOrder().getSymbol());
        List<Trade> trades = null;

        if ("SUBMIT".equals(event.getAction())) {
            // 1. 先写入 WAL (写前日志)
            engine.getPersistence().appendOrderSubmit(event.getOrder());

            // 2. 提交订单
            trades = engine.submitOrder(event.getOrder());

            // 3. 记录成交到 WAL
            if (trades != null) {
                for (Trade trade : trades) {
                    engine.getPersistence().appendTrade(trade);
                }
            }

            // 4. 建立 clientOrderId 映射
            if (event.getOrder().getClientOrderId() != null) {
                clientOrderIndex.put(
                        event.getOrder().getSymbol(),
                        event.getOrder().getClientOrderId(),
                        event.getOrder().getOrderId()
                );
            }

            log.debug("Order submitted: {}, trades: {}", event.getOrder().getOrderId(),
                    trades != null ? trades.size() : 0);

        } else if ("CANCEL".equals(event.getAction())) {
            String orderId = event.getOrder().getOrderId();

            // 检查是否是 clientOrderId
            String clientOrderId = event.getOrder().getClientOrderId();
            if (clientOrderId != null && !clientOrderId.isBlank()) {
                orderId = clientOrderIndex.getOrderId(event.getOrder().getSymbol(), clientOrderId);
                if (orderId == null) {
                    log.warn("Cancel failed: clientOrderId not found: {}", clientOrderId);
                    return;
                }
            }

            // 1. 先写入 WAL
            engine.getPersistence().appendOrderCancel(orderId);

            // 2. 执行撤单
            boolean success = engine.cancelOrder(orderId);

            if (success) {
                // 3. 清理 clientOrderId 映射
                clientOrderIndex.remove(orderId);
                log.debug("Order cancelled: {}", orderId);
            } else {
                log.warn("Cancel failed: order not found: {}", orderId);
            }
        }
    }

    /**
     * 获取 ClientOrderIndex 单例
     */
    public static ClientOrderIndex getClientOrderIndex() {
        return clientOrderIndex;
    }
}
