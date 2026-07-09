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

@Slf4j
@RequiredArgsConstructor
public class OrderEventHandler implements EventHandler<OrderEvent> {

    private static final ClientOrderIndex clientOrderIndex = new ClientOrderIndex();

    private final MatchingEngineManager matchingEngineManager;
    private final RiskManager riskManager;

    @Nullable
    private final FreezeService freezeService;

    @Nullable
    private final KlineLogger klineLogger;

    @Override
    public void onEvent(OrderEvent event, long sequence, boolean endOfBatch) {
        var engine = matchingEngineManager.getEngine(event.getOrder().getSymbol());
        List<Trade> trades = null;

        if ("SUBMIT".equals(event.getAction())) {
            RiskCheckResult riskResult = riskManager.checkOrder(event.getOrder());
            if (!riskResult.isAllowed()) {
                Order order = event.getOrder();
                order.setStatus(OrderStatus.REJECTED);
                order.setRejectReason(riskResult.getRejectReason());
                log.warn("Order rejected by risk control: orderId={}, reason={}",
                        order.getOrderId(), riskResult.getRejectReason());
                return;
            }

            if (freezeService != null && event.getOrder().getUserId() != null
                    && event.getOrder().getPrice() != null) {
                BigDecimal orderAmount = event.getOrder().getPrice()
                        .multiply(event.getOrder().getQuantity());

                boolean frozen = freezeService.preFreeze(
                        Long.parseLong(event.getOrder().getUserId()),
                        getSettlementCurrency(event.getOrder()),
                        event.getOrder().getOrderId(),
                        orderAmount
                );

                if (!frozen) {
                    Order order = event.getOrder();
                    order.setStatus(OrderStatus.REJECTED);
                    order.setRejectReason("余额冻结失败");
                    log.warn("Order rejected: freeze failed: orderId={}",
                            order.getOrderId());
                    return;
                }
            }

            trades = engine.submitOrder(event.getOrder());

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

                    if (freezeService != null) {
                        freezeService.deduct(trade.getBuyOrderId(), fillAmount);
                        freezeService.deduct(trade.getSellOrderId(), fillAmount);
                    }

                    riskManager.onOrderTraded(event.getOrder(), fillAmount);
                }
            }

            if (event.getOrder().getClientOrderId() != null) {
                clientOrderIndex.put(
                        event.getOrder().getSymbol(),
                        event.getOrder().getClientOrderId(),
                        event.getOrder().getOrderId()
                );
            }

            riskManager.onOrderSubmitted(event.getOrder());

            log.info("Order submitted: {}, trades: {}", event.getOrder().getOrderId(),
                    trades != null ? trades.size() : 0);

        } else if ("CANCEL".equals(event.getAction())) {
            String orderId = event.getOrder().getOrderId();

            String clientOrderId = event.getOrder().getClientOrderId();
            if (clientOrderId != null && !clientOrderId.isBlank()) {
                orderId = clientOrderIndex.getOrderId(event.getOrder().getSymbol(), clientOrderId);
                if (orderId == null) {
                    log.warn("Cancel failed: clientOrderId not found: {}", clientOrderId);
                    return;
                }
            }

            String userId = event.getOrder().getUserId();
            RiskCheckResult riskResult = riskManager.checkCancel(orderId, userId);
            if (!riskResult.isAllowed()) {
                log.warn("Cancel rejected by risk control: orderId={}, reason={}",
                        orderId, riskResult.getRejectReason());
                return;
            }

            boolean success = engine.cancelOrder(orderId);

            if (success) {
                if (freezeService != null) {
                    freezeService.unfreeze(orderId);
                }

                clientOrderIndex.remove(orderId);
                riskManager.onOrderCanceled(orderId, userId);
                log.debug("Order cancelled: {}", orderId);
            } else {
                log.warn("Cancel failed: order not found: {}", orderId);
            }
        }
    }

    private String getSettlementCurrency(Order order) {
        String symbol = order.getSymbol();
        if (symbol == null || symbol.isBlank()) {
            return "USDT";
        }
        int idx = symbol.indexOf("USDT");
        return idx > 0 ? "USDT" : symbol;
    }

    public static ClientOrderIndex getClientOrderIndex() {
        return clientOrderIndex;
    }
}
