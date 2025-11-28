package com.matching.disruptor;

import com.matching.core.domain.DepthLevel;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class MarketDataEvent {
    public String symbol;
    public final List<DepthLevel> bids = new ArrayList<>(32);
    public final List<DepthLevel> asks = new ArrayList<>(32);
    public long sequence;
    public long timestamp;

    public void reset() {
        symbol = null;
        bids.clear();
        asks.clear();
        sequence = 0;
        timestamp = 0;
    }
}
