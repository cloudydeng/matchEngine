package com.matching.account;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 账户余额信息
 */
@Data
public class Account {
    private Long userId;
    private String currency;      // 币种，如 BTC, USDT
    private BigDecimal balance;    // 总余额
    private BigDecimal available;  // 可用余额
    private BigDecimal frozen;    // 冻结余额
    private BigDecimal filled;    // 已成交余额

    /**
     * 计算可用余额 (校验用）
     */
    public boolean hasEnoughAvailable(BigDecimal amount) {
        return available.compareTo(amount) >= 0;
    }

    /**
     * 计算冻结余额是否充足
     */
    public boolean hasEnoughFrozen(BigDecimal amount) {
        return frozen.compareTo(amount) >= 0;
    }
}
