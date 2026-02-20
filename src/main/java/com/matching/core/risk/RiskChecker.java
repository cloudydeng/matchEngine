package com.matching.core.risk;

import com.matching.core.domain.Order;

/**
 * 风控检查器接口
 */
public interface RiskChecker {
    /**
     * 检查订单是否允许提交
     * @param order 待检查的订单
     * @return 风控检查结果
     */
    RiskCheckResult checkOrder(Order order);

    /**
     * 检查撤单是否允许
     * @param orderId 订单ID
     * @param userId 用户ID
     * @return 风控检查结果
     */
    default RiskCheckResult checkCancel(String orderId, String userId) {
        return RiskCheckResult.allowed();
    }

    /**
     * 获取风控检查器名称（用于日志）
     */
    String getName();
}
