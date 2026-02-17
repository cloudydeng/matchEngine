package com.matching.core.index;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;

/**
 * ClientOrderId 到 OrderId 的映射索引
 * 支持按用户自定义ID查询和取消订单
 */
@Slf4j
public class ClientOrderIndex {

    // key: symbol + ":" + clientOrderId, value: orderId
    private final ConcurrentHashMap<String, String> clientIndex = new ConcurrentHashMap<>();

    // key: orderId, value: clientOrderId (反向映射，支持快速删除)
    private final ConcurrentHashMap<String, String> reverseIndex = new ConcurrentHashMap<>();

    /**
     * 建立映射关系
     * @param symbol 交易对
     * @param clientOrderId 用户自定义订单ID
     * @param orderId 系统生成的订单ID
     */
    public void put(String symbol, String clientOrderId, String orderId) {
        if (clientOrderId == null || clientOrderId.isBlank()) {
            return;
        }
        String key = buildKey(symbol, clientOrderId);
        clientIndex.put(key, orderId);
        reverseIndex.put(orderId, clientOrderId);
    }

    /**
     * 根据 clientOrderId 查找 orderId
     */
    public String getOrderId(String symbol, String clientOrderId) {
        if (clientOrderId == null || clientOrderId.isBlank()) {
            return null;
        }
        return clientIndex.get(buildKey(symbol, clientOrderId));
    }

    /**
     * 根据 orderId 查找 clientOrderId
     */
    public String getClientOrderId(String orderId) {
        return reverseIndex.get(orderId);
    }

    /**
     * 删除映射关系（订单取消或成交时调用）
     */
    public void remove(String orderId) {
        String clientOrderId = reverseIndex.remove(orderId);
        if (clientOrderId != null) {
            // 从反向索引中找到 symbol (需要额外存储)
            // 简化实现：遍历 clientIndex 删除
            clientIndex.entrySet().removeIf(e -> e.getValue().equals(orderId));
        }
    }

    /**
     * 删除映射关系（已知 symbol 和 clientOrderId 时使用）
     */
    public void removeByClientOrderId(String symbol, String clientOrderId) {
        String orderId = clientIndex.remove(buildKey(symbol, clientOrderId));
        if (orderId != null) {
            reverseIndex.remove(orderId);
        }
    }

    /**
     * 构建索引键
     */
    private String buildKey(String symbol, String clientOrderId) {
        return symbol + ":" + clientOrderId;
    }

    /**
     * 清空索引（用于恢复或测试）
     */
    public void clear() {
        clientIndex.clear();
        reverseIndex.clear();
    }

    /**
     * 获取索引大小
     */
    public int size() {
        return clientIndex.size();
    }
}
