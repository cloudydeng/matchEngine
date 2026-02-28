# 推送微服务设计

## 一、服务架构

```
┌──────────────────────────────────────────────────────────────────┐
│                      API Gateway / Load Balancer        │
├──────────────────────────────────────────────────────────────────┤
│                                                         │
│   ┌─────────────┐  ┌─────────────┐  ┌─────────────┐     │
│   │  撮合引擎   │  │  账务服务    │  │  推送服务    │     │
│   │  (Match)    │  │ (Account)   │  │ (Push)     │     │
│   │ :8080        │  │ :8081       │  │ :8082       │     │
│   │              │  │              │  │              │     │
│   │ - 订单处理   │◄───────►│ - 冻结/扣款 │  │ - 连接管理   │     │
│   │ - 撮合执行   │  │              │◄─────────────►│ - 消息推送   │     │
│   │              │  │              │  │              │     │
│   │    ↓ MQ       │  │              │  │              │     │
│   │              │  │              │  │              │     │
│   │              │  │              │  │              │     │
│   └─────────────┘  └─────────────┘  └─────────────┘     │
│                                                         │
├──────────────────────────────────────────────────────────────────┤
│              ┌─────────────┐  ┌─────────────┐            │
│              │  Redis 集群  │  │  Kafka 集群│            │
│              │  (Sentinel)  │  │  (高可用）  │            │
│              └─────────────┘  └─────────────┘            │
└──────────────────────────────────────────────────────────────────┘
```

---

## 二、服务拆分

### 撮合引擎服务 (Match Service)
- REST API: `/api/order`, `/api/cancel`
- 消息生产者：发送订单事件、行情事件
- Disruptor: 订单处理
- 订单簿撮合

### 账务服务 (Account Service)
- REST API: `/api/account/balance`, `/api/account/freeze`
- 冻结服务：预冻结、扣款、释放
- Redis: 余额存储

### 推送服务 (Push Service) - **新建**
- WebSocket: 行情连接管理
- 消息消费者：Kafka 订阅行情事件
- 连接池管理：WebSocket 连接池
- 广播机制：高效推送

---

## 三、消息队列设计

### Kafka Topic 设计

```
┌────────────────────────────────────────────────────────────┐
│                  Kafka Cluster                   │
├────────────────────────────────────────────────────────────┤
│                                                         │
│  Topic: market-data                                      │
│    ┌──────────────────────────────────────────────────┐     │
│    │  分区 Partition 0-3                  │     │
│    │  - 按交易对分片                       │     │
│    │  - BTCUSDT → partition 0             │     │
│    │  - ETHUSDT → partition 1             │     │
│    └──────────────────────────────────────────────────┘     │
│                                                         │
│  Topic: account-event                                   │
│    ┌──────────────────────────────────────────────────┐     │
│    │  分区 Partition 0-1                  │     │
│    │  - 按 userId 分片                     │     │
│    └──────────────────────────────────────────────────┘     │
│                                                         │
│  Topic: order-event                                    │
│    ┌──────────────────────────────────────────────────┐     │
│    │  分区 Partition 0-2                  │     │
│    │  - 按 symbol 分片                     │     │
│    └──────────────────────────────────────────────────┘     │
└────────────────────────────────────────────────────────────┘
```

### 消息格式

#### 1. 行情推送消息
```json
{
  "eventType": "DEPTH_UPDATE",
  "timestamp": 1234567890,
  "symbol": "BTCUSDT",
  "data": {
    "bids": [
      {"price": "50000.00", "qty": "0.5"},
      {"price": "49999.00", "qty": "1.0"}
    ],
    "asks": [
      {"price": "50001.00", "qty": "0.3"},
      {"price": "50002.00", "qty": "0.5"}
    ]
  }
}
```

#### 2. 成交推送消息
```json
{
  "eventType": "TRADE",
  "timestamp": 1234567890,
  "symbol": "BTCUSDT",
  "data": {
    "tradeId": "TRD123",
    "price": "50000.00",
    "quantity": "0.1",
    "side": "BUY",
    "buyOrderId": "BTCUSDT_1",
    "sellOrderId": "BTCUSDT_2"
  }
}
```

#### 3. 订单推送消息
```json
{
  "eventType": "ORDER_UPDATE",
  "timestamp": 1234567890,
  "symbol": "BTCUSDT",
  "data": {
    "orderId": "BTCUSDT_1",
    "status": "FILLED",
    "filledQty": "0.1",
    "remainQty": "0"
  }
}
```

---

## 四、推送服务设计

### WebSocket 连接管理

```java
// 连接池管理
@Service
public class ConnectionManager {
    // 按交易对分组的连接
    // key: symbol, value: Set<WebSocketSession>
    private ConcurrentHashMap<String, Set<WebSocketSession>> symbolConnections = new ConcurrentHashMap<>();

    // 按用户分组的连接
    private ConcurrentHashMap<String, Set<WebSocketSession>> userConnections = new ConcurrentHashMap<>();

    // 添加连接
    public void addConnection(String symbol, Long userId, WebSocketSession session) {
        symbolConnections.computeIfAbsent(symbol, k -> ConcurrentHashMap.newKeySet()).add(session);
        if (userId != null) {
            userConnections.computeIfAbsent(userId.toString(), k -> ConcurrentHashMap.newKeySet()).add(session);
        }
    }

    // 移除连接
    public void removeConnection(String symbol, Long userId, WebSocketSession session) {
        if (symbolConnections.containsKey(symbol)) {
            symbolConnections.get(symbol).remove(session);
        }
        if (userId != null && userConnections.containsKey(userId.toString())) {
            userConnections.get(userId.toString()).remove(session);
        }
    }

    // 广播到所有订阅者
    public void broadcast(String symbol, String message) {
        Set<WebSocketSession> sessions = symbolConnections.get(symbol);
        if (sessions != null) {
            sessions.forEach(session -> sendMessage(session, message));
        }
    }

    private void sendMessage(WebSocketSession session, String message) {
        try {
            session.sendMessage(new TextMessage(message));
        } catch (Exception e) {
            // 发送失败，记录日志
        }
    }
}
```

### Kafka 消费者设计

```java
@Service
public class MarketDataConsumer {
    @KafkaListener(topics = "market-data", groupId = "push-service-group")
    public void consumeMarketData(String message) {
        // 解析消息
        MarketDataEvent event = parseMessage(message);

        // 广播到所有订阅该交易对的 WebSocket 连接
        connectionManager.broadcast(event.getSymbol(), event.toJson());
    }
}
```

---

## 五、协议设计

### WebSocket 握手协议

```http
# 升级协议
GET /ws/market?symbol=BTCUSDT&token=xxx HTTP/1.1
Host: push.example.com:8082
Upgrade: websocket
Connection: Upgrade
Sec-WebSocket-Key: dGhlIHNhbXBsZW4vUFc=
Sec-WebSocket-Version: 13
Sec-WebSocket-Protocol: v1.encrypted, v1.json
```

### 心跳机制

```javascript
// 客户端发送心跳
setInterval(() => {
  ws.send(JSON.stringify({
    type: 'ping',
    timestamp: Date.now()
  }));
}, 30000);  // 30秒一次

// 服务端响应
ws.on('message', (data) => {
  if (data.type === 'pong') {
    lastPongTime = Date.now();
  }
});

// 服务端检测超时
setInterval(() => {
  if (Date.now() - lastPongTime > 60000) {
    ws.close();  // 60秒无响应，断开
  }
}, 10000);  // 10秒检查一次
```

### 断线重连

```javascript
class WebSocketClient {
  constructor(url, token) {
    this.url = url;
    this.token = token;
    this.reconnectAttempts = 0;
    this.maxReconnectAttempts = 5;
    this.connect();
  }

  connect() {
    const ws = new WebSocket(`${this.url}?token=${this.token}`);

    ws.on('open', () => {
      this.reconnectAttempts = 0;
      this.subscribeSymbols();
    });

    ws.on('close', () => {
      if (this.reconnectAttempts < this.maxReconnectAttempts) {
        const delay = Math.pow(2, this.reconnectAttempts) * 1000;
        setTimeout(() => {
          this.reconnectAttempts++;
          this.connect();
        }, delay);
      }
    });

    ws.on('error', (err) => {
      console.error('WebSocket error:', err);
    });
  }

  subscribeSymbols() {
    // 发送订阅请求
    this.ws.send(JSON.stringify({
      type: 'subscribe',
      symbols: ['BTCUSDT', 'ETHUSDT']
    }));
  }
}
```

---

## 六、限流与保护

### 连接限流

```yaml
# 推送服务限流配置
spring:
  cloud:
    gateway:
      routes:
        - id: push-service
          uri: lb://PUSH-SERVICE
          predicates:
            - Path=/ws/**
          filters:
            - name: WebSocketRateLimiter
              args:
                reconnect-rate: 10     # 每秒最多重连数
                max-connections: 1000   # 单 IP 最大连接数
```

### 消息限流

```java
// 防止消息风暴
@Aspect
@Component
public class RateLimitAspect {

    @Around("execution(* com.matching.push..*.*(..))")
    public Object limitMessageRate(ProceedingJoinPoint pjp) throws Throwable {
        String sessionId = getSessionId();

        // 使用 Redis 滑动窗口限流
        if (isRateLimited(sessionId)) {
            throw new RateLimitException("发送频率过快");
        }

        return pjp.proceed();
    }

    private boolean isRateLimited(String sessionId) {
        // 1秒内最多发送 100 条消息
        String key = "msg:rate:" + sessionId;
        Long count = redisTemplate.opsForValue().increment(key);
        redisTemplate.expire(key, 1, TimeUnit.SECONDS);
        return count > 100;
    }
}
```

---

## 七、监控指标

| 指标 | 说明 | 告警阈值 |
|--------|------|----------|
| ws_connections_total | WebSocket 连接总数 | > 10000 |
| ws_connections_per_symbol | 每交易对连接数 | > 5000 |
| ws_messages_total | 消息发送总数 | N/A |
| ws_messages_per_second | 消息发送速率 | > 100000/s |
| kafka_consumer_lag | Kafka 消费延迟 | > 100ms |
| kafka_consume_rate | Kafka 消费速率 | < 1000/s |

---

## 八、部署架构

```
┌────────────────────────────────────────────────────────────┐
│              Nginx / API Gateway               │
├────────────────────────────────────────────────────────────┤
│                                                         │
│  ┌─────────────────────────────────────────────┐        │
│  │           负载均衡器             │        │
│  │ ┌───────────┬───────────┬──────────┐ │        │
│  │ │ Match Sv  │ Account Sv │ Push Sv  │ │        │
│  │ │ 8080      │ 8081      │ 8082     │ │        │
│  │ └───────────┴───────────┴──────────┘ │        │
│  └─────────────────────────────────────────────┘        │
└────────────────────────────────────────────────────────────┘
```

### Nginx WebSocket 配置

```nginx
upstream push_backend {
    server push1:8082;
    server push2:8082;
    server push3:8082;

    # 健康检查
    check interval=3000 rise=2 fall=3 timeout=1000;
}

map $http_upgrade $connection_upgrade {
    default upgrade;
    "" close;
}

server {
    listen 8082 ssl;

    location /ws/market {
        proxy_pass http://push_backend;
        proxy_http_version 1.1;

        # WebSocket 升级
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";

        # 超时配置
        proxy_connect_timeout 60s;
        proxy_send_timeout 3600s;
        proxy_read_timeout 3600s;

        # 启用 gzip
        gzip on;
        gzip_types text/plain application/json;
    }
}
```
