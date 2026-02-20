package com.matching.core.risk;

import com.matching.core.domain.Order;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 风控管理器
 * 统一调度所有风控检查器
 */
@Slf4j
@Component
public class RiskManager {

    @Autowired
    private UserRateLimitChecker userRateLimitChecker;

    @Autowired
    private OrderAmountLimitChecker orderAmountLimitChecker;

    @Autowired
    private PriceRangeChecker priceRangeChecker;

    /**
     * 检查订单是否允许提交
     * 按优先级依次检查，任一检查失败即拒绝
     *
     * @param order 待检查的订单
     * @return 风控检查结果
     */
    public RiskCheckResult checkOrder(Order order) {
        List<RiskChecker> checkers = getOrderedCheckers();

        for (RiskChecker checker : checkers) {
            RiskCheckResult result = checker.checkOrder(order);
            if (!result.isAllowed()) {
                log.warn("Risk check failed: checker={}, reason={}, errorCode={}",
                        checker.getName(), result.getRejectReason(), result.getErrorCode());
                return result;
            }
        }

        log.debug("Order passed all risk checks: orderId={}", order.getOrderId());
        return RiskCheckResult.allowed();
    }

    /**
     * 检查撤单是否允许
     */
    public RiskCheckResult checkCancel(String orderId, String userId) {
        List<RiskChecker> checkers = getOrderedCheckers();

        for (RiskChecker checker : checkers) {
            RiskCheckResult result = checker.checkCancel(orderId, userId);
            if (!result.isAllowed()) {
                log.warn("Cancel risk check failed: checker={}, reason={}",
                        checker.getName(), result.getRejectReason());
                return result;
            }
        }

        return RiskCheckResult.allowed();
    }

    /**
     * 订单提交成功后调用（更新统计数据）
     */
    public void onOrderSubmitted(Order order) {
        if (order.getUserId() != null) {
            orderAmountLimitChecker.incrementOpenOrder(order.getSymbol(), order.getUserId());
        }
    }

    /**
     * 订单成交后调用（更新统计数据）
     */
    public void onOrderTraded(Order order, java.math.BigDecimal tradeAmount) {
        if (order.getUserId() != null) {
            orderAmountLimitChecker.addDailyVolume(order.getUserId(), tradeAmount);
        }
    }

    /**
     * 订单撤单后调用（更新统计数据）
     */
    public void onOrderCanceled(String orderId, String userId) {
        if (userId != null) {
            orderAmountLimitChecker.checkCancel(orderId, userId);
        }
    }

    /**
     * 定时清理任务
     * 每分钟调用一次，清理过期的频率窗口
     */
    public void cleanup() {
        userRateLimitChecker.cleanup();
    }

    /**
     * 获取按优先级排序的风控检查器
     * 检查顺序：
     * 1. 频率限制（最快失败）
     * 2. 数量限制
     * 3. 价格检查
     */
    private List<RiskChecker> getOrderedCheckers() {
        List<RiskChecker> checkers = new ArrayList<>();
        checkers.add(userRateLimitChecker);
        checkers.add(orderAmountLimitChecker);
        checkers.add(priceRangeChecker);
        return checkers;
    }
}
