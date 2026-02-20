package com.matching.core.risk;

/**
 * 风控错误码枚举
 */
public enum RiskErrorCode {
    // 订单验证相关
    INVALID_QUANTITY("R001", "数量无效"),
    INVALID_PRICE("R002", "价格无效"),
    INVALID_SYMBOL("R003", "交易对不存在"),
    INVALID_ORDER_TYPE("R004", "订单类型无效"),

    // 余额相关
    INSUFFICIENT_BALANCE("R101", "余额不足"),
    INSUFFICIENT_AVAILABLE("R102", "可用余额不足"),

    // 频率限制相关
    ORDER_RATE_LIMIT("R201", "下单频率超限"),
    CANCEL_RATE_LIMIT("R202", "撤单频率超限"),
    API_RATE_LIMIT("R203", "API调用频率超限"),

    // 数量限制相关
    TOO_MANY_OPEN_ORDERS("R301", "挂单数量超限"),
    ORDER_AMOUNT_TOO_LARGE("R302", "单笔金额超限"),
    DAILY_LIMIT_EXCEEDED("R303", "日交易量超限"),

    // 价格相关
    PRICE_OUT_OF_RANGE("R401", "价格超出允许范围"),
    PRICE_PRECISION_INVALID("R402", "价格精度不符合要求"),
    QUANTITY_PRECISION_INVALID("R403", "数量精度不符合要求"),

    // 账户相关
    ACCOUNT_FROZEN("R501", "账户已冻结"),
    ACCOUNT_SUSPENDED("R502", "账户已暂停"),
    BLACKLISTED("R503", "用户在黑名单中"),

    // 市场相关
    MARKET_HALTED("M001", "市场暂停交易"),
    PRICE_VOLATILITY_HIGH("M002", "价格波动过大"),
    SYSTEM_OVERLOADED("M003", "系统负载过高");

    private final String code;
    private final String message;

    RiskErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    @Override
    public String toString() {
        return code + ": " + message;
    }
}
