package com.matching.disruptor;

import com.lmax.disruptor.EventHandler;
import com.matching.core.domain.Order;
import com.matching.core.domain.OrderStatus;
import com.matching.core.domain.Trade;
import com.matching.core.engine.MatchingEngineManager;
import com.matching.core.index.ClientOrderIndex;
import com.matching.core.risk.RiskCheckResult;
import com.matching.core.risk.RiskManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

/**
 * 订单事件处理器
 * 处理订单提交、取消，集成风控检查，并记录到 WAL
 */
@Slf4j
public class OrderEventHandler implements EventHandler<OrderEvent> {

    // 全局 ClientOrderIndex 单例
    private static final ClientOrderIndex clientOrderIndex = new ClientOrderIndex();

    @Autowired
    private RiskManager riskManager;

    @Override
    public void onEvent(OrderEvent event, long sequence, boolean endOfBatch) throws Exception {
        var engine = MatchingEngineManager.getEngine(event.getOrder().getSymbol());
        List<Trade> trades = null;

        if ("SUBMIT".equals(event.getAction())) {
            // 1. 风控检查（所有检查通过才继续）
            RiskCheckResult riskResult = riskManager.checkOrder(event.getOrder());
            if (!riskResult.isAllowed()) {
                // 风控拒绝，设置订单状态并返回
                Order order = event.getOrder();
                order.setStatus(OrderStatus.REJECTED);
                order.setRejectReason(riskResult.getRejectReason());
                log.warn("Order rejected by risk control: orderId={}, reason={}",
                        order.getOrderId(), riskResult.getRejectReason());
                return;
            }

            // 2. 先写入 WAL (写前日志)
            engine.getPersistence().appendOrderSubmit(event.getOrder());

            // 3. 提交订单
            trades = engine.submitOrder(event.getOrder());

            // 4. 记录成交到 WAL
            if (trades != null) {
                for (Trade trade : trades) {
                    engine.getPersistence().appendTrade(trade);
                    // 更新风控统计
                    riskManager.onOrderTraded(event.getOrder(),
                            trade.getPrice().multiply(trade.getQuantity()));
                }
            }

            // 5. 建立 clientOrderId 映射
            if (event.getOrder().getClientOrderId() != null) {
                clientOrderIndex.put(
                        event.getOrder().getSymbol(),
                        event.getOrder().getClientOrderId(),
                        event.getOrder().getOrderId()
                );
            }

            // 6. 更新风控统计（订单提交成功）
            riskManager.onOrderSubmitted(event.getOrder());

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

            // 1. 风控检查
            String userId = event.getOrder().getUserId();
            RiskCheckResult riskResult = riskManager.checkCancel(orderId, userId);
            if (!riskResult.isAllowed()) {
                log.warn("Cancel rejected by risk control: orderId={}, reason={}",
                        orderId, riskResult.getRejectReason());
                return;
            }

            // 2. 先写入 WAL
            engine.getPersistence().appendOrderCancel(orderId);

            // 3. 执行撤单
            boolean success = engine.cancelOrder(orderId);

            if (success) {
                // 4. 清理 clientOrderId 映射
                clientOrderIndex.remove(orderId);
                // 5. 更新风控统计
                riskManager.onOrderCanceled(orderId, userId);
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
