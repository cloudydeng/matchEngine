package com.matching.market;

import com.matching.core.domain.Trade;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@Component
@Slf4j
public class KlineLogger {

    private static final long ONE_MINUTE_MS = 60_000L;
    private final Map<String, Candle> candles = new HashMap<>();

    public synchronized void onTrade(Trade trade) {
        if (trade == null || trade.getSymbol() == null || trade.getPrice() == null || trade.getQuantity() == null) {
            return;
        }

        long now = System.currentTimeMillis();
        long bucketStart = (now / ONE_MINUTE_MS) * ONE_MINUTE_MS;
        String symbol = trade.getSymbol();

        Candle candle = candles.get(symbol);
        if (candle == null || candle.bucketStart != bucketStart) {
            if (candle != null) {
                log.info("KLINE_1m_CLOSE symbol={}, bucketStart={}, open={}, high={}, low={}, close={}, volume={}, turnover={}, trades={}",
                        symbol, candle.bucketStart, candle.open, candle.high, candle.low, candle.close,
                        candle.volume, candle.turnover, candle.trades);
            }
            candle = new Candle(bucketStart, trade.getPrice(), trade.getQuantity());
            candles.put(symbol, candle);
        } else {
            candle.high = candle.high.max(trade.getPrice());
            candle.low = candle.low.min(trade.getPrice());
            candle.close = trade.getPrice();
            candle.volume = candle.volume.add(trade.getQuantity());
            candle.turnover = candle.turnover.add(trade.getPrice().multiply(trade.getQuantity()));
            candle.trades++;
        }

        log.info("KLINE_1m_UPDATE symbol={}, bucketStart={}, open={}, high={}, low={}, close={}, volume={}, turnover={}, trades={}",
                symbol, candle.bucketStart, candle.open, candle.high, candle.low, candle.close,
                candle.volume, candle.turnover, candle.trades);
    }

    private static class Candle {
        long bucketStart;
        BigDecimal open;
        BigDecimal high;
        BigDecimal low;
        BigDecimal close;
        BigDecimal volume;
        BigDecimal turnover;
        long trades;

        Candle(long bucketStart, BigDecimal openPrice, BigDecimal firstQty) {
            this.bucketStart = bucketStart;
            this.open = openPrice;
            this.high = openPrice;
            this.low = openPrice;
            this.close = openPrice;
            this.volume = firstQty;
            this.turnover = openPrice.multiply(firstQty);
            this.trades = 1;
        }
    }
}
