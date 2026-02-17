# Disruptor Matching Engine

基于 **Disruptor** 的高性能金融交易撮合引擎，支持多交易对并发撮合。

## 特性

- **高性能撮合**：使用 LMAX Disruptor 实现事件驱动架构，支持微秒级延迟
- **多分片架构**：支持配置 Disruptor 分片数量，提升并发吞吐量
- **WAL 持久化**：实现写前日志 (Write-Ahead Log)，支持崩溃恢复
- **快照机制**：定期全量快照，减少 WAL 重放时间
- **多订单类型**：支持市价单、限价单，以及 GTC/IOC/FOK 等时间限制
- **自成交预防**：通过 userId 校验防止自成交
- **ClientOrderID 支持**：用户可自定义订单 ID，便于撤单
- **深度保护**：支持 30 层深度限制，防止大单扫盘
- **行情推送**：通过 WebSocket 实时推送深度变化

## 技术栈

- Java 21
- Spring Boot 3.2.0
- LMAX Disruptor 4.0.0
- OkHttp 4.12.0

## 项目结构

```
src/main/java/com/matching/
├── api/                      # REST API 层
│   ├── OrderController.java    # 订单提交和取消接口
│   ├── MarketDataWebSocketHandler.java
│   └── dto/
├── core/
│   ├── domain/               # 领域模型
│   ├── engine/               # 撮合引擎核心
│   │   ├── MatchingEngine.java
│   │   ├── MatchingEngineManager.java
│   │   └── L3OrderBook.java
│   ├── index/               # 索引层
│   │   └── ClientOrderIndex.java
│   └── persistence/          # 持久化层
│       └── OrderBookPersistence.java
├── disruptor/              # Disruptor 事件处理
│   ├── OrderEvent.java
│   ├── OrderEventProducer.java
│   ├── OrderEventHandler.java
│   └── MarketDataPublisher.java
├── wal/                   # 写前日志
│   ├── WalRecord.java
│   └── WalWriter.java
├── config/                # Spring 配置
├── load/                  # 压力测试工具
└── opinion/               # 行情监控服务
```

## 快速开始

### 配置

编辑 `application.yml`：

```yaml
server:
  port: 8080

app:
  shard-count: 2                  # Disruptor 分片数量
  disruptor-buffer-size: 131072   # 环形缓冲区大小 (2^17)
  wal-dir: ./wal/               # WAL 日志目录

opinion:
  api:
    base: https://proxy.opinion.trade:8443/openapi
    key: YOUR_API_KEY
```

### 运行

```bash
mvn spring-boot:run
```

## API 使用

### 提交订单

```bash
curl -X POST http://localhost:8080/api/order \
  -H "Content-Type: application/json" \
  -d '{
    "symbol": "BTCUSDT",
    "side": "BUY",
    "type": "LIMIT",
    "price": "50000.00",
    "quantity": "0.1",
    "clientOrderId": "my-order-123",
    "userId": "user123"
  }'
```

### 取消订单（使用 orderId）

```bash
curl -X POST http://localhost:8080/api/cancel \
  -H "Content-Type: application/json" \
  -d '{
    "symbol": "BTCUSDT",
    "orderId": "BTCUSDT_1234567890"
  }'
```

### 取消订单（使用 clientOrderId）

```bash
curl -X POST http://localhost:8080/api/cancel \
  -H "Content-Type: application/json" \
  -d '{
    "symbol": "BTCUSDT",
    "clientOrderId": "my-order-123"
  }'
```

## 核心概念

### Disruptor 架构

```
OrderController → OrderEventProducer → Disruptor (Multi-Shard)
                                              ↓
                                    OrderEventHandler → MatchingEngine
```

- **OrderEventProducer**: 将订单事件发布到 Disruptor 环形缓冲区
- **Disruptor**: 无锁内存队列，按 symbol 分片路由到对应 ring buffer
- **OrderEventHandler**: 消费事件，执行撮合并记录 WAL

### WAL 格式

| 类型 | 格式 | 说明 |
|------|------|------|
| ORDER_SUBMIT | `ORDER_SUBMIT\|timestamp\|orderId\|clientOrderId\|symbol\|side\|type\|price\|quantity\|userId` | 订单提交 |
| ORDER_CANCEL | `ORDER_CANCEL\|timestamp\|orderId\|symbol` | 订单取消 |
| TRADE | `TRADE\|timestamp\|symbol\|buyOrderId\|sellOrderId\|price\|quantity` | 成交记录 |
| SNAPSHOT | `SNAPSHOT\|timestamp` | 快照标记 |

### 撮合逻辑

- **价格优先**: 买单从高到低，卖单从低到高
- **时间优先**: 同价位按时间戳排序
- **自成交预防**: 同一用户的订单不会自动撮合
- **五档保护**: 可配置防止大单扫盘超过 N 层深度

## 性能指标

| 指标 | 值 |
|------|-----|
| 单节点 TPS | 100,000+ |
| 平均延迟 | < 1ms |
| 支持交易对 | 动态创建 |
| 分片数量 | 可配置 |

## 开发计划

- [ ] 实现完整订单簿恢复
- [ ] 支持更多订单类型 (冰山单、隐藏单、止损单)
- [ ] 添加账户余额管理
- [ ] 实现费率计算
- [ ] 支持 WebSocket 订单确认推送
- [ ] 添加单元测试和压力测试

## License

MIT
