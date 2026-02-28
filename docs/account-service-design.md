# 账务微服务设计

## 一、服务架构

```
┌──────────────────────────────────────────────────────────────────┐
│                      API Gateway (Nginx/Kong)        │
├──────────────────────────────────────────────────────────────────┤
│                                                         │
│   ┌─────────────┐     ┌─────────────┐             │
│   │  撮合引擎     │     │  账务服务     │             │
│   │  (Match)     │     │ (Account)     │             │
│   │  :8080        │     │ :8081         │             │
│   │               │     │               │             │
│   │  - 订单提交    │     │  - 余额查询     │             │
│   │  - 订单取消    │     │  - 预冻结       │             │
│   │  - 撮合执行    │     │  - 扣款         │             │
│   │  - 行情推送    │     │  - 释放冻结     │             │
│   │               │     │  - 充值记录     │             │
│   │               │     │  - 提现记录     │             │
│   └─────────────┘     └─────────────┘             │
│                                                         │
├──────────────────────────────────────────────────────────────────┤
│              ┌─────────────┐   ┌─────────────┐            │
│              │  Redis 集群  │   │  MySQL 集群 │            │
│              │  (Sentinel)  │   │  (主从)    │            │
│              └─────────────┘   └─────────────┘            │
└──────────────────────────────────────────────────────────────────┘
```

---

## 二、服务拆分

### 撮合引擎服务 (Match Service)
| 模块 | 功能 |
|------|------|
| OrderController | REST API |
| OrderEventHandler | Disruptor 事件处理 |
| MatchingEngine | 撮合核心 |
| L3OrderBook | 三级订单簿 |
| MarketDataPublisher | 行情发布 |
| WAL + Snapshot | 持久化 |

### 账务服务 (Account Service)
| 模块 | 功能 |
|------|------|
| AccountController | 余额查询 API |
| FreezeController | 冻结操作 API |
| DepositController | 充值记录 API |
| WithdrawController | 提现记录 API |
| FreezeService | 冻结服务 |
| BalanceService | 余额服务 |
| TradeService | 交易记录服务 |

---

## 三、API 接口设计

### 1. 余额查询
```http
GET /api/account/balance
请求: { userId, currency }
响应: {
    "code": 0,
    "data": {
        "userId": 123,
        "currency": "USDT",
        "balance": "10000.00",
        "available": "9500.00",
        "frozen": "500.00",
        "filled": "0.00"
    }
}
```

### 2. 预冻结（下单时调用）
```http
POST /api/account/freeze
请求: {
    "userId": 123,
    "currency": "USDT",
    "orderId": "BTCUSDT_123",
    "amount": "100.00",
    "action": "freeze"
}
响应: {
    "code": 0,
    "message": "success"
}
```

### 3. 扣款（成交后调用）
```http
POST /api/account/freeze
请求: {
    "userId": 123,
    "currency": "USDT",
    "orderId": "BTCUSDT_123",
    "amount": "100.00",
    "action": "deduct"
}
响应: {
    "code": 0,
    "message": "success"
}
```

### 4. 释放冻结（撤单时调用）
```http
POST /api/account/freeze
请求: {
    "userId": 123,
    "currency": "USDT",
    "orderId": "BTCUSDT_123",
    "amount": "100.00",
    "action": "unfreeze"
}
响应: {
    "code": 0,
    "message": "success"
}
```

### 5. 充值记录
```http
POST /api/account/deposit
请求: {
    "userId": 123,
    "currency": "USDT",
    "amount": "1000.00",
    "txHash": "0x123...",
    "network": "TRC20"
}
响应: {
    "code": 0,
    "data": {
        "depositId": "DEP123",
        "status": "PENDING"
    }
}
```

### 6. 提现记录
```http
POST /api/account/withdraw
请求: {
    "userId": 123,
    "currency": "USDT",
    "amount": "500.00",
    "address": "Txxx..."
}
响应: {
    "code": 0,
    "data": {
        "withdrawId": "WD123",
        "status": "PENDING"
    }
}
```

---

## 四、服务间通信

### 1. 撮合引擎 → 账务服务

```java
// 在 Match Service 中调用
@Service
public class OrderEventHandler {
    @Autowired
    private AccountClient accountClient;  // Feign/RestTemplate

    public void onEvent(OrderEvent event) {
        // 下单前预冻结
        if ("SUBMIT".equals(event.getAction())) {
            FreezeRequest request = FreezeRequest.builder()
                    .userId(order.getUserId())
                    .currency("USDT")
                    .orderId(order.getOrderId())
                    .amount(order.getAmount())
                    .action("freeze")
                    .build();

            FreezeResponse response = accountClient.freeze(request);
            if (!response.isSuccess()) {
                // 冻结失败，拒绝订单
                return;
            }
        }
    }
}
```

### 2. 账务服务 → 撮合引擎

```java
// 成交后通知撮合引擎（可选，如推送余额更新）
@Service
public class BalanceService {
    @Autowired
    private MatchClient matchClient;  // Feign/RestTemplate

    public void onBalanceChanged(Long userId, String currency) {
        // 通知撮合引擎更新用户状态
        BalanceUpdateRequest request = BalanceUpdateRequest.builder()
                .userId(userId)
                .currency(currency)
                .build();

        matchClient.updateBalance(request);
    }
}
```

---

## 五、数据库表设计

### 1. accounts 表（账户表）
```sql
CREATE TABLE accounts (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL COMMENT '用户ID',
    currency VARCHAR(20) NOT NULL COMMENT '币种',
    balance DECIMAL(30,18) NOT NULL DEFAULT 0 COMMENT '总余额',
    available DECIMAL(30,18) NOT NULL DEFAULT 0 COMMENT '可用余额',
    frozen DECIMAL(30,18) NOT NULL DEFAULT 0 COMMENT '冻结余额',
    filled DECIMAL(30,18) NOT NULL DEFAULT 0 COMMENT '已成交',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_currency (user_id, currency),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账户表';
```

### 2. account_freeze_records 表（冻结记录表）
```sql
CREATE TABLE account_freeze_records (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL COMMENT '用户ID',
    currency VARCHAR(20) NOT NULL COMMENT '币种',
    order_id VARCHAR(64) NOT NULL COMMENT '订单ID',
    amount DECIMAL(30,18) NOT NULL COMMENT '金额',
    type TINYINT NOT NULL COMMENT '1=冻结, 2=解冻, 3=扣款',
    status TINYINT NOT NULL COMMENT '1=成功, 2=失败',
    reason VARCHAR(255) COMMENT '失败原因',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id),
    INDEX idx_order_id (order_id),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='冻结操作记录';
```

### 3. deposits 表（充值记录表）
```sql
CREATE TABLE deposits (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    deposit_id VARCHAR(64) NOT NULL COMMENT '充值ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    currency VARCHAR(20) NOT NULL COMMENT '币种',
    amount DECIMAL(30,18) NOT NULL COMMENT '金额',
    tx_hash VARCHAR(128) COMMENT '链上交易哈希',
    network VARCHAR(32) COMMENT '网络(TRC20/ERC20)',
    status TINYINT NOT NULL COMMENT '1=待确认, 2=已确认, 3=失败',
    confirmations INT DEFAULT 0 COMMENT '确认数',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_deposit_id (deposit_id),
    INDEX idx_user_id (user_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='充值记录';
```

### 4. withdrawals 表（提现记录表）
```sql
CREATE TABLE withdrawals (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    withdraw_id VARCHAR(64) NOT NULL COMMENT '提现ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    currency VARCHAR(20) NOT NULL COMMENT '币种',
    amount DECIMAL(30,18) NOT NULL COMMENT '金额',
    address VARCHAR(128) COMMENT '提现地址',
    tx_hash VARCHAR(128) COMMENT '链上交易哈希',
    network VARCHAR(32) COMMENT '网络(TRC20/ERC20)',
    status TINYINT NOT NULL COMMENT '1=待审核, 2=审核通过, 3=已提交, 4=成功, 5=失败',
    fee DECIMAL(30,18) DEFAULT 0 COMMENT '手续费',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_withdraw_id (withdraw_id),
    INDEX idx_user_id (user_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='提现记录';
```

### 5. trades 表（交易记录表）
```sql
CREATE TABLE trades (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    trade_id VARCHAR(64) NOT NULL COMMENT '交易ID',
    symbol VARCHAR(32) NOT NULL COMMENT '交易对',
    side TINYINT NOT NULL COMMENT '1=BUY, 2=SELL',
    price DECIMAL(30,18) NOT NULL COMMENT '成交价',
    quantity DECIMAL(30,18) NOT NULL COMMENT '数量',
    amount DECIMAL(30,18) NOT NULL COMMENT '成交额',
    buy_user_id BIGINT NOT NULL COMMENT '买方用户ID',
    sell_user_id BIGINT NOT NULL COMMENT '卖方用户ID',
    buy_order_id VARCHAR(64) COMMENT '买单ID',
    sell_order_id VARCHAR(64) COMMENT '卖单ID',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_trade_id (trade_id),
    INDEX idx_symbol (symbol),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交易记录';
```

---

## 六、Redis 设计

### Key 规范
```
account:{userId}:{currency}           # 账户余额 Hash
freeze:order:{orderId}                # 订单冻结 Hash
user_orders:{userId}                   # 用户订单集合 Set
user_balance_version:{userId}           # 余额版本号（乐观锁）
```

### 过期策略
| Key | 过期时间 |
|-----|----------|
| account:{userId}:{currency} | 永久不过期 |
| freeze:order:{orderId} | 24 小时 |
| user_orders:{userId} | 7 天 |

---

## 七、分布式事务

### 方案一：TCC (Try-Confirm-Cancel)
```java
// 撮合引擎作为发起方
@TccTransaction
public void submitOrder(Order order) {
    // Try 阶段：预冻结
    accountClient.freeze(order);

    // 提交到订单簿
    matchingEngine.submitOrder(order);

    // Confirm 阶段：扣款（成交后）
    accountClient.deduct(trade);

    // Cancel 阶段：撤单时释放
    accountClient.unfreeze(orderId);
}
```

### 方案二：可靠消息（推荐）
```
1. 撮合引擎发送"订单已成交"消息到 MQ
2. 账务服务消费消息，执行扣款
3. 扣款成功后发送 ACK
4. 撮合引擎收到 ACK 后返回成功
5. 如果超时未收到 ACK，重试
```

---

## 八、容错与降级

### 1. 服务降级
| 场景 | 策略 |
|--------|--------|
| 账务服务不可用 | 本地缓存兜底 + 异步补单 |
| Redis 故障 | 降级到 MySQL（带锁） |
| MQ 不可用 | 直连 HTTP 接口 |

### 2. 限流保护
```yaml
# 账务服务限流配置
spring:
  cloud:
    gateway:
      routes:
        - id: account-service
          uri: lb://ACCOUNT-SERVICE
          predicates:
            - Path=/api/account/**
          filters:
            - name: RequestRateLimiter
              args:
                redis-rate-limiter:
                  replenishRate: 100
                  burstCapacity: 200
```

### 3. 熔断机制
```yaml
# Hystrix/Resilience4j 配置
resilience4j:
  circuitbreaker:
    instances:
      accountClient:
        sliding-window-size: 20
        failure-rate-threshold: 50
        wait-duration-in-open-state: 30s
        permitted-number-of-calls-in-half-open-state: 10
```

---

## 九、监控告警

### 关键指标
| 指标 | 告警阈值 |
|--------|----------|
| 冻结失败率 | > 0.1% |
| 扣款失败率 | > 0.01% |
| 服务响应时间 | P99 > 100ms |
| 服务可用性 | < 99.9% |

### 告警方式
- Prometheus + Grafana 监控
- 钉钉/企业微信 告警
- 邮件告警
