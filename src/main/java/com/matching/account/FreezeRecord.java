package com.matching.account;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 冻结操作记录
 */
@Data
@Builder
@AllArgsConstructor
public class FreezeRecord {
    private Long id;
    private Long userId;
    private String currency;
    private String orderId;
    private BigDecimal amount;
    private FreezeType type;
    private FreezeStatus status;
    private Instant createdAt;

    public enum FreezeType {
        FREEZE,     // 冻结
        UNFREEZE,   // 解冻（撤单）
        DEDUCT       // 扣款（成交）
    }

    public enum FreezeStatus {
        SUCCESS,
        FAILED
    }
}
