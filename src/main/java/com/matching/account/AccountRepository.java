package com.matching.account;

import java.util.Optional;

/**
 * 账户数据访问接口
 * 实现可以是 MySQL 或其他存储
 */
public interface AccountRepository {

    /**
     * 查找用户指定币种的账户
     */
    Optional<Account> findByUserIdAndCurrency(Long userId, String currency);

    /**
     * 保存冻结记录
     */
    void saveFreezeRecord(FreezeRecord record);
}
