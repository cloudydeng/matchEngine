package com.matching.disruptor;

import com.lmax.disruptor.EventHandler;
import com.matching.account.FreezeService;
import com.matching.core.domain.Order;
import com.matching.core.domain.OrderStatus;
import com.matching.core.domain.Trade;
import com.matching.core.engine.MatchingEngineManager;
import com.matching.core.index.ClientOrderIndex;
import com.matching.core.risk.RiskCheckResult;
import com.matching.core.risk.RiskManager;
import com.matching.market.KlineLogger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.util.List;

/**
 * 订单事件处理器
 * 处理订单提交、取消，集成风控和冻结，并记录到 WAL
 */
@Slf4j
@RequiredArgsConstructor
public class OrderEventHandler implements EventHandler<OrderEvent> {

    // 全局 ClientOrderIndex 单例
    private static final ClientOrderIndex clientOrderIndex = new ClientOrderIndex();

    private final RiskManager riskManager;

    @Nullable
    private final FreezeService freezeService;  // 账户冻结服务

    @Nullable
    private final KlineLogger klineLogger;

    @Override
    public void onEvent(OrderEvent event, long sequence, boolean endOfBatch) throws Exception {
        var engine = MatchingEngineManager.getEngine(event.getOrder().getSymbol());
        List<Trade> trades = null;

        if ("SUBMIT".equals(event.getAction())) {
            //1. 风控检查（所有检查通过才继续）
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

            //2. 预冻结余额（挂单前必须冻结）
            if (freezeService != null && event.getOrder().getUserId() != null) {
                // 计算订单金额
                BigDecimal orderAmount = event.getOrder().getPrice()
                        .multiply(event.getOrder().getQuantity());

                boolean frozen = freezeService.preFreeze(
                        Long.parseLong(event.getOrder().getUserId()),
                        getSettlementCurrency(event.getOrder()),
                        event.getOrder().getOrderId(),
                        orderAmount
                );

                if (!frozen) {
                    // 冻结失败，拒绝订单
                    Order order = event.getOrder();
                    order.setStatus(OrderStatus.REJECTED);
                    order.setRejectReason("余额冻结失败");
                    log.warn("Order rejected: freeze failed: orderId={}",
                            order.getOrderId());
                    return;
                }
            }

            //3. 先写入 WAL (写前日志)
            engine.getPersistence().appendOrderSubmit(event.getOrder());

            //4. 提交订单
            trades = engine.submitOrder(event.getOrder());

            //5. 记录成交到 WAL 并扣款
            if (trades != null) {
                for (Trade trade : trades) {
                    engine.getPersistence().appendTrade(trade);
                    BigDecimal fillAmount = trade.getPrice().multiply(trade.getQuantity());
                    log.info("Trade executed: symbol={}, tradeId={}, price={}, qty={}, buyOrderId={}, sellOrderId={}",
                            trade.getSymbol(), trade.getTradeId(), trade.getPrice(), trade.getQuantity(),
                            trade.getBuyOrderId(), trade.getSellOrderId());
                    if (klineLogger != null) {
                        klineLogger.onTrade(trade);
                    }

                    // 扣款（成交后）
                    if (freezeService != null) {
                        freezeService.deduct(trade.getBuyOrderId(), fillAmount);
                        freezeService.deduct(trade.getSellOrderId(), fillAmount);
                    }

                    // 更新风控统计
                    riskManager.onOrderTraded(event.getOrder(), fillAmount);
                }
            }

            //6. 建立 clientOrderId 映射
            if (event.getOrder().getClientOrderId() != null) {
                clientOrderIndex.put(
                        event.getOrder().getSymbol(),
                        event.getOrder().getClientOrderId(),
                        event.getOrder().getOrderId()
                );
            }

            //7. 更新风控统计（订单提交成功）
            riskManager.onOrderSubmitted(event.getOrder());

            log.info("Order submitted: {}, trades: {}", event.getOrder().getOrderId(),
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

            //1. 风控检查
            String userId = event.getOrder().getUserId();
            RiskCheckResult riskResult = riskManager.checkCancel(orderId, userId);
            if (!riskResult.isAllowed()) {
                log.warn("Cancel rejected by risk control: orderId={}, reason={}",
                        orderId, riskResult.getRejectReason());
                return;
            }

            //2. 先写入 WAL
            engine.getPersistence().appendOrderCancel(orderId);

            //3. 执行撤单
            boolean success = engine.cancelOrder(orderId);

            if (success) {
                //4. 释放冻结（撤单必须释放预冻结）
                if (freezeService != null) {
                    freezeService.unfreeze(orderId);
                }

                //5. 清理 clientOrderId 映射
                clientOrderIndex.remove(orderId);
                //6. 更新风控统计
                riskManager.onOrderCanceled(orderId, userId);
                log.debug("Order cancelled: {}", orderId);
            } else {
                log.warn("Cancel failed: order not found: {}", orderId);
            }
        }
    }

    /**
     * 获取结算币种（简化处理）
     * 实际应该根据交易对确定：BTCUSDT -> 计价币为 USDT
     */
    private String getSettlementCurrency(Order order) {
        // 简化处理：假设交易对格式为 XXXUSDT
        String symbol = order.getSymbol();
        if (symbol == null || symbol.isBlank()) {
            return "USDT";  // 默认
        }
        // 提取 USDT 部分
        int idx = symbol.indexOf("USDT");
        return idx > 0 ? "USDT" : symbol;
    }

    /**
     * 获取 ClientOrderIndex 单例
     */
    public static ClientOrderIndex getClientOrderIndex() {
        return clientOrderIndex;
    }
}
