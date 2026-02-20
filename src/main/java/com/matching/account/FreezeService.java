package com.matching.account;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 冻结服务
 * 负责：预冻结、扣款、释放冻结
 * 使用 Redis + MySQL 混合存储
 */
@Slf4j
@Service
public class FreezeService {

    @Autowired(required = false)
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired(required = false)
    private AccountRepository accountRepository;  // MySQL 存储层（可选）

    // Redis Key 前缀
    private static final String ACCOUNT_PREFIX = "account:";
    private static final String ORDER_PREFIX = "order:";
    private static final String USER_ORDERS_PREFIX = "user_orders:";

    /**
     * 预冻结余额（下单时调用）
     * @param userId 用户ID
     * @param currency 币种
     * @param orderId 订单ID
     * @param amount 冻结金额
     * @return 是否冻结成功
     */
    public boolean preFreeze(Long userId, String currency, String orderId, BigDecimal amount) {
        String accountKey = getAccountKey(userId, currency);
        String orderKey = getOrderKey(orderId);

        // 检查可用余额
        Object availableObj = redisTemplate.opsForHash().get(accountKey, "available");
        if (availableObj == null) {
            // 账户不存在，先初始化
            initializeAccount(userId, currency);
            return preFreeze(userId, currency, orderId, amount);
        }

        BigDecimal available = new BigDecimal(availableObj.toString());
        if (available.compareTo(amount) < 0) {
            log.warn("Insufficient available balance: userId={}, available={}, required={}",
                    userId, available, amount);
            return false;
        }

        try {
            // 执行冻结（事务操作）
            redisTemplate.opsForHash().increment(accountKey, "frozen", amount.doubleValue());
            redisTemplate.opsForHash().increment(accountKey, "available", -amount.doubleValue());

            // 记录订单信息
            redisTemplate.opsForHash().put(orderKey, "userId", userId.toString());
            redisTemplate.opsForHash().put(orderKey, "currency", currency);
            redisTemplate.opsForHash().put(orderKey, "amount", amount.toString());
            redisTemplate.expire(orderKey, 24, TimeUnit.HOURS);  // 24小时过期

            // 添加到用户订单集合
            String userOrdersKey = getUserOrdersKey(userId);
            redisTemplate.opsForSet().add(userOrdersKey, orderId);

            log.info("Pre-freeze success: userId={}, currency={}, orderId={}, amount={}",
                    userId, currency, orderId, amount);

            // 异步记录到 MySQL
            saveFreezeRecordAsync(userId, currency, orderId, amount,
                    FreezeRecord.FreezeType.FREEZE, FreezeRecord.FreezeStatus.SUCCESS);

            return true;

        } catch (Exception e) {
            log.error("Pre-freeze failed: userId={}, orderId={}, amount={}",
                    userId, orderId, amount, e);

            saveFreezeRecordAsync(userId, currency, orderId, amount,
                    FreezeRecord.FreezeType.FREEZE, FreezeRecord.FreezeStatus.FAILED);

            return false;
        }
    }

    /**
     * 扣款（成交后调用）
     * @param orderId 订单ID
     * @param fillAmount 成交金额
     * @return 是否扣款成功
     */
    public boolean deduct(String orderId, BigDecimal fillAmount) {
        // 1. 从订单缓存获取信息
        String orderKey = getOrderKey(orderId);
        Map<Object, Object> orderInfo = redisTemplate.opsForHash().entries(orderKey);

        if (orderInfo == null || orderInfo.isEmpty()) {
            log.warn("Order not found for deduct: orderId={}", orderId);
            return false;
        }

        String userIdStr = (String) orderInfo.get("userId");
        String currency = (String) orderInfo.get("currency");

        if (userIdStr == null || currency == null) {
            log.warn("Order info incomplete: orderId={}", orderId);
            return false;
        }

        Long userId = Long.parseLong(userIdStr);
        String accountKey = getAccountKey(userId, currency);

        try {
            // 检查冻结余额是否足够
            Object frozenObj = redisTemplate.opsForHash().get(accountKey, "frozen");
            BigDecimal frozen = frozenObj != null ? new BigDecimal(frozenObj.toString()) : BigDecimal.ZERO;

            if (frozen.compareTo(fillAmount) < 0) {
                log.warn("Insufficient frozen balance: userId={}, frozen={}, required={}",
                        userId, frozen, fillAmount);
                return false;
            }

            // 执行扣款
            redisTemplate.opsForHash().increment(accountKey, "frozen", -fillAmount.doubleValue());
            redisTemplate.opsForHash().increment(accountKey, "filled", fillAmount.doubleValue());

            log.info("Deduct success: userId={}, currency={}, orderId={}, amount={}",
                    userId, currency, fillAmount);

            // 异步记录到 MySQL
            saveFreezeRecordAsync(userId, currency, orderId, fillAmount,
                    FreezeRecord.FreezeType.DEDUCT, FreezeRecord.FreezeStatus.SUCCESS);

            return true;

        } catch (Exception e) {
            log.error("Deduct failed: userId={}, orderId={}, amount={}",
                    userId, orderId, fillAmount, e);

            saveFreezeRecordAsync(userId, currency, orderId, fillAmount,
                    FreezeRecord.FreezeType.DEDUCT, FreezeRecord.FreezeStatus.FAILED);

            return false;
        }
    }

    /**
     * 释放冻结（撤单时调用）
     * @param orderId 订单ID
     * @return 是否释放成功
     */
    public boolean unfreeze(String orderId) {
        // 1. 从订单缓存获取信息
        String orderKey = getOrderKey(orderId);
        Map<Object, Object> orderInfo = redisTemplate.opsForHash().entries(orderKey);

        if (orderInfo == null || orderInfo.isEmpty()) {
            log.warn("Order info not found for unfreeze: orderId={}", orderId);
            return false;
        }

        String userIdStr = (String) orderInfo.get("userId");
        String currency = (String) orderInfo.get("currency");
        String amountStr = (String) orderInfo.get("amount");

        if (userIdStr == null || currency == null || amountStr == null) {
            log.warn("Order info incomplete for unfreeze: orderId={}", orderId);
            return false;
        }

        Long userId = Long.parseLong(userIdStr);
        BigDecimal amount = new BigDecimal(amountStr);
        String accountKey = getAccountKey(userId, currency);

        try {
            // 执行释放冻结
            redisTemplate.opsForHash().increment(accountKey, "frozen", -amount.doubleValue());
            redisTemplate.opsForHash().increment(accountKey, "available", amount.doubleValue());

            // 移除订单记录
            redisTemplate.delete(orderKey);

            // 从用户订单集合中移除
            String userOrdersKey = getUserOrdersKey(userId);
            redisTemplate.opsForSet().remove(userOrdersKey, orderId);

            log.info("Unfreeze success: userId={}, currency={}, orderId={}, amount={}",
                    userId, currency, orderId, amount);

            // 异步记录到 MySQL
            saveFreezeRecordAsync(userId, currency, orderId, amount,
                    FreezeRecord.FreezeType.UNFREEZE, FreezeRecord.FreezeStatus.SUCCESS);

            return true;

        } catch (Exception e) {
            log.error("Unfreeze failed: userId={}, orderId={}, amount={}",
                    userId, orderId, amount, e);

            saveFreezeRecordAsync(userId, currency, orderId, amount,
                    FreezeRecord.FreezeType.UNFREEZE, FreezeRecord.FreezeStatus.FAILED);

            return false;
        }
    }

    /**
     * 获取账户余额
     */
    public Account getAccount(Long userId, String currency) {
        String accountKey = getAccountKey(userId, currency);

        Map<Object, Object> data = redisTemplate.opsForHash().entries(accountKey);

        if (data == null || data.isEmpty()) {
            // Redis 没有，尝试从数据库加载
            if (accountRepository != null) {
                return accountRepository.findByUserIdAndCurrency(userId, currency)
                        .orElse(new Account());
            }
            return new Account();
        }

        Account account = new Account();
        account.setUserId(userId);
        account.setCurrency(currency);
        account.setBalance(new BigDecimal(data.getOrDefault("balance", "0").toString()));
        account.setAvailable(new BigDecimal(data.getOrDefault("available", "0").toString()));
        account.setFrozen(new BigDecimal(data.getOrDefault("frozen", "0").toString()));
        account.setFilled(new BigDecimal(data.getOrDefault("filled", "0").toString()));

        return account;
    }

    /**
     * 检查可用余额是否充足
     */
    public boolean checkAvailable(Long userId, String currency, BigDecimal amount) {
        Account account = getAccount(userId, currency);
        return account.hasEnoughAvailable(amount);
    }

    // ==================== 私有方法 ====================

    private String getAccountKey(Long userId, String currency) {
        return ACCOUNT_PREFIX + userId + ":" + currency;
    }

    private String getOrderKey(String orderId) {
        return ORDER_PREFIX + orderId;
    }

    private String getUserOrdersKey(Long userId) {
        return USER_ORDERS_PREFIX + userId;
    }

    /**
     * 初始化账户
     */
    private void initializeAccount(Long userId, String currency) {
        String key = getAccountKey(userId, currency);

        redisTemplate.opsForHash().put(key, "balance", "0");
        redisTemplate.opsForHash().put(key, "available", "0");
        redisTemplate.opsForHash().put(key, "frozen", "0");
        redisTemplate.opsForHash().put(key, "filled", "0");
        redisTemplate.expire(key, 30, TimeUnit.DAYS);  // 30天过期

        log.info("Account initialized: userId={}, currency={}", userId, currency);
    }

    /**
     * 从数据库加载账户
     */
    private Account loadFromDatabase(Long userId, String currency) {
        if (accountRepository == null) {
            return new Account();  // 返回空账户
        }
        return accountRepository.findByUserIdAndCurrency(userId, currency)
                .orElse(new Account());
    }

    /**
     * 异步保存冻结记录
     */
    private void saveFreezeRecordAsync(Long userId, String currency, String orderId,
                                      BigDecimal amount, FreezeRecord.FreezeType type,
                                      FreezeRecord.FreezeStatus status) {
        if (accountRepository == null) {
            return;
        }

        FreezeRecord record = FreezeRecord.builder()
                .userId(userId)
                .currency(currency)
                .orderId(orderId)
                .amount(amount)
                .type(type)
                .status(status)
                .createdAt(Instant.now())
                .build();

        try {
            accountRepository.saveFreezeRecord(record);
        } catch (Exception e) {
            log.error("Failed to save freeze record", e);
        }
    }
}
