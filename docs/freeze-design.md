# 账户冻结与扣款流程设计

## 一、账户模型

### 核心概念
| 概念 | 说明 |
|--------|------|
| 总余额 (Balance) | 用户账户总资产 |
| 可用余额 (Available) | 可用于下单的余额 |
| 冻结余额 (Frozen) | 已挂单但未成交的余额 |
| 冻结粒度 | 按订单粒度冻结，成交后按实际成交扣款 |

### 余额公式
```
Balance = Available + Frozen + Filled
Available = Balance - Frozen - Filled
```

---

## 二、完整流程

### 订单提交流程
```
1. 收到订单请求
   ↓
2. 风控检查
   ↓
3. 余额检查（Available >= 订单金额）
   ↓
4. 预冻结（Frozen += 订单金额, Available -= 订单金额）
   ↓
5. 写入订单簿
   ↓
6. 返回成功
```

### 订单成交流程
```
1. 订单簿撮合成交
   ↓
2. 撮合引擎通知账户系统
   ↓
3. 实际扣款（Frozen -= 成交金额, Filled += 成交金额）
   ↓
4. 释放剩余冻结（如果有）
   ↓
5. 更新对手方账户（充值/扣款）
   ↓
6. 返回成交结果
```

### 订单撤单流程
```
1. 收到撤单请求
   ↓
2. 验证订单存在且未成交
   ↓
3. 释放冻结（Frozen -= 订单金额, Available += 订单金额）
   ↓
4. 从订单簿移除
   ↓
5. 返回成功
```

### 部分成交流程
```
1. 订单部分成交
   ↓
2. 实际扣款（Frozen -= 成交金额, Filled += 成交金额）
   ↓
3. 剩余部分继续挂单（Frozen 保留剩余）
   ↓
4. 更新订单状态为 PARTIALLY_FILLED
```

---

## 三、Redis vs 数据库

### 存储选型

| 维度 | Redis | 数据库 (MySQL) |
|------|--------|----------------|
| 读写性能 | 极高 (微秒级) | 中等 (毫秒级) |
| 数据持久化 | 需定期备份 | 原生支持 |
| 事务支持 | 支持 (MULTI/EXEC) | 支持 (ACID) |
| 适用场景 | 高频交易余额、临时冻结 | 账户表、交易历史 |

### 推荐方案：**混合架构**

```
┌─────────────────────────────────────────────────────────┐
│                   应用层                         │
├─────────────────────────────────────────────────────────┤
│  Redis (热数据)       │
│  - 可用余额、冻结余额              │
│  - 挂单信息缓存                 │
│  - 高频更新，低延迟                 │
├─────────────────────────────────────────────────────────┤
│  MySQL (冷数据)              │
│  - 账户表 (accounts)                  │
│  - 资产表 (assets)                    │
│  - 交易历史表 (trades)                 │
│  - 充提记录 (deposits/withdrawals)      │
│  - 定期同步，最终一致性                │
└─────────────────────────────────────────────────────────┘
```

---

## 四、Redis 数据结构设计

### Key 命名规范
```
account:{userId}:{currency}          # 账户余额
frozen:{userId}:{currency}           # 冻结余额
order:{orderId}                    # 订单信息
user_orders:{userId}                # 用户订单列表（Set）
```

### Redis 操作示例
```redis
# 预冻结
HINCRBY account:123:BTC frozen 0.5
HINCRBY account:123:BTC available -0.5

# 扣款（成交后）
HINCRBY frozen:123:BTC -0.5
HINCRBY filled:123:BTC 0.5

# 释放冻结（撤单）
HINCRBY account:123:BTC available 0.5
HINCRBY account:123:BTC frozen -0.5
```

---

## 五、数据库表设计

### accounts 表
```sql
CREATE TABLE accounts (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    currency VARCHAR(20) NOT NULL,
    balance DECIMAL(30,18) NOT NULL DEFAULT 0,
    available DECIMAL(30,18) NOT NULL DEFAULT 0,
    frozen DECIMAL(30,18) NOT NULL DEFAULT 0,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_currency (user_id, currency)
);
```

### account_freeze_records 表（冻结记录）
```sql
CREATE TABLE account_freeze_records (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    currency VARCHAR(20) NOT NULL,
    order_id VARCHAR(64) NOT NULL,
    amount DECIMAL(30,18) NOT NULL,
    type TINYINT NOT NULL COMMENT '1=冻结, 2=解冻, 3=扣款',
    status TINYINT NOT NULL COMMENT '1=成功, 2=失败',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

---

## 六、关键点

### 1. 幂等性
- 冻结操作需要幂等，避免重复冻结
- 使用订单唯一 ID 作为幂等键

### 2. 一致性
- Redis 和 MySQL 需要定期同步
- 使用 Canal 或定时任务实现 CDC

### 3. 容错
- Redis 不可用时降级到数据库
- 使用数据库事务兜底

### 4. 并发控制
- 使用 Redis Lua 脚本保证原子性
- 或使用乐观锁 (CAS)
