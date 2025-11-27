package com.matching.disruptor;

import com.lmax.disruptor.EventHandler;
import com.matching.core.domain.Trade;
import com.matching.core.engine.MatchingEngineManager;
import com.matching.wal.WalWriter;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

@Slf4j
public class OrderEventHandler implements EventHandler<OrderEvent> {



    @Override
    public void onEvent(OrderEvent event, long sequence, boolean endOfBatch) throws Exception {
        // WAL 先写（写前日志）
        var engine = MatchingEngineManager.getEngine(event.getOrder().getSymbol());
        List<Trade> trades = null;
        if ("SUBMIT".equals(event.getAction())) {
            trades = engine.submitOrder(event.getOrder());
            log.info("success publish to " + event.getOrder().getOrderId());
            if (trades != null) {
                log.info("Trade: success");
            }
        } else if ("CANCEL".equals(event.getAction())) {
            // 撤单：需实现 OrderId -> price/qty 映射，这里简化
            engine.cancelOrder(event.getOrder().getOrderId());
        }
    }
}