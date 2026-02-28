# 用户系统微服务设计

## 一、服务架构

```
┌──────────────────────────────────────────────────────────────────┐
│                      API Gateway / Load Balancer        │
├──────────────────────────────────────────────────────────────────┤
│                                                         │
│   ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  │
│   │  撮合引擎      │  │  账务服务      │  │  推送服务      │  │  用户服务      │  │
│   │  (Match)    │  │ (Account)   │  │ (Push)     │  │  (User)     │  │
│   │ :8080        │  │ :8081       │  │ :8082       │  │  :8083       │  │
│   │              │  │               │  │              │  │              │  │
│   │              │  │               │  │              │  │              │  │
│   │              │  │               │  │              │  │              │  │
│   │    ┌────────────────────────────┐         │  │              │  │              │  │
│   │   用户认证 & 权限管理        │         │  │              │  │              │  │
│   │    ┌─────────────────────┐  │         │  │              │  │              │  │
│   │   │ 注册    登录  │  │         │  │              │  │              │  │
│   │   └─────────────────────┘  │         │  │              │  │              │  │
│   └────────────────────────────┘         │  │              │  │              │  │              │  │
│                                         │  │              │  │              │  │
└──────────────────────────────────────────────────────────────────┘
```

---

## 二、服务拆分

### 用户服务 (User Service)
| 模块 | 功能 |
|------|------|
| UserController | 用户 REST API |
| AuthService | 认证授权 |
| UserService | 用户管理 |
| PermissionService | 权限管理 |
| TokenService | Token 管理 |
| DeviceService | 设备管理 |
| ActivityService | 用户活动记录 |

---

## 三、API 接口设计

### 1. 用户注册
```http
POST /api/user/register
请求: {
    "username": "user123",
    "email": "user@example.com",
    "password": "password123",
    "confirmPassword": "password123",
    "referralCode": "REF123"  // 可选
}
响应: {
    "code": 0,
    "message": "success",
    "data": {
        "userId": 123,
        "username": "user123"
    }
}
```

### 2. 用户登录
```http
POST /api/user/login
请求: {
    "username": "user123",
    "password": "password123",
    "deviceInfo": {
        "deviceId": "xxx",
        "deviceType": "WEB",
        "ipAddress": "1.2.3.4"
    }
}
响应: {
    "code": 0,
    "message": "success",
    "data": {
        "userId": 123,
        "username": "user123",
        "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
        "refreshToken": "eyJhbGciOiJIUzI1NiJ9...",
        "expiresIn": 7200
    }
}
```

### 3. Token 刷新
```http
POST /api/user/refresh
请求: {
    "refreshToken": "eyJhbGciOiJIUzI1NiJ9..."
}
响应: {
    "code": 0,
    "data": {
        "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
        "expiresIn": 7200
    }
}
```

### 4. 用户信息查询
```http
GET /api/user/info
请求头: Authorization: Bearer {accessToken}
响应: {
    "code": 0,
    "data": {
        "userId": 123,
        "username": "user123",
        "email": "user@example.com",
        "status": "ACTIVE",
        "createdAt": "2024-01-01T00:00:00Z"
    }
}
```

### 5. 修改密码
```http
POST /api/user/password
请求头: Authorization: Bearer {accessToken}
请求: {
    "oldPassword": "password123",
    "newPassword": "password456"
}
响应: {
    "code": 0,
    "message": "密码修改成功"
}
```

### 6. 设备管理
```http
POST /api/user/device
请求: {
    "deviceId": "xxx",
    "deviceType": "WEB",
    "deviceName": "Chrome on macOS"
}
响应: {
    "code": 0,
    "data": {
        "deviceId": "xxx",
        "status": "ACTIVE"
    }
}
```

---

## 四、数据库表设计

### 1. users 表（用户表）
```sql
CREATE TABLE users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL COMMENT '用户ID',
    username VARCHAR(64) NOT NULL COMMENT '用户名',
    email VARCHAR(128) NOT NULL COMMENT '邮箱',
    password_hash VARCHAR(128) NOT NULL COMMENT '密码哈希',
    salt VARCHAR(64) COMMENT '盐值',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '1=激活, 2=禁用, 3=锁定',
    email_verified TINYINT DEFAULT 0 COMMENT '邮箱验证',
    phone VARCHAR(32) COMMENT '手机号',
    phone_verified TINYINT DEFAULT 0 COMMENT '手机验证',
    referral_code VARCHAR(32) COMMENT '推荐码',
    referrer_id BIGINT COMMENT '推荐人ID',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_username (username),
    UNIQUE KEY uk_email (email),
    INDEX idx_referral (referral_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';
```

### 2. user_devices 表（设备表）
```sql
CREATE TABLE user_devices (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL COMMENT '用户ID',
    device_id VARCHAR(128) NOT NULL COMMENT '设备ID',
    device_type VARCHAR(32) COMMENT '设备类型:WEB, IOS, ANDROID',
    device_name VARCHAR(128) COMMENT '设备名称',
    ip_address VARCHAR(64) COMMENT 'IP地址',
    user_agent VARCHAR(512) COMMENT 'User Agent',
    last_login_at TIMESTAMP COMMENT '最后登录时间',
    status TINYINT DEFAULT 1 COMMENT '1=活跃, 2=禁用',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_device (device_id),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户设备表';
```

### 3. tokens 表（Token 表）
```sql
CREATE TABLE tokens (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL COMMENT '用户ID',
    access_token VARCHAR(512) NOT NULL COMMENT '访问 Token',
    refresh_token VARCHAR(512) NOT NULL COMMENT '刷新 Token',
    token_type TINYINT DEFAULT 1 COMMENT '1=ACCESS, 2=REFRESH',
    device_id VARCHAR(128) COMMENT '设备ID',
    expires_at TIMESTAMP NOT NULL COMMENT '过期时间',
    revoked TINYINT DEFAULT 0 COMMENT '是否撤销',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id),
    INDEX idx_access_token (access_token),
    INDEX idx_refresh_token (refresh_token),
    INDEX idx_expires_at (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Token 表';
```

### 4. user_permissions 表（权限表）
```sql
CREATE TABLE user_permissions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL COMMENT '用户ID',
    permission_code VARCHAR(64) NOT NULL COMMENT '权限码',
    resource_type VARCHAR(32) COMMENT '资源类型',
    resource_id VARCHAR(64) COMMENT '资源ID',
    granted TINYINT DEFAULT 1 COMMENT '是否授予',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id),
    INDEX idx_permission_code (permission_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户权限表';
```

### 5. user_activities 表（用户活动表）
```sql
CREATE TABLE user_activities (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL COMMENT '用户ID',
    activity_type VARCHAR(32) NOT NULL COMMENT '活动类型',
    activity_data JSON COMMENT '活动数据（JSON格式）',
    ip_address VARCHAR(64) COMMENT 'IP地址',
    user_agent VARCHAR(512) COMMENT 'User Agent',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id),
    INDEX idx_activity_type (activity_type),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户活动记录';
```

---

## 五、Redis 设计

### Key 规范
```
user:info:{userId}              # 用户基本信息
user:token:{accessToken}       # Token 信息
user:sessions:{userId}         # 用户会话 Set
user:permissions:{userId}       # 用户权限 Set
user:login:fail:{ip}         # 登录失败计数
user:verify:{email}           # 邮箱验证码
user:verify:{phone}           # 手机验证码
rate:limit:{type}:{key}       # 限流计数
```

### 过期策略
| Key | 过期时间 |
|-----|----------|
| user:info:{userId} | 1 小时 |
| user:token:{accessToken} | 2 小时 |
| user:sessions:{userId} | 24 小时 |
| user:verify:{email} | 5 分钟 |
| user:verify:{phone} | 5 分钟 |
| rate:limit:* | 1 分钟 |

---

## 六、认证与授权设计

### JWT Token 设计
```java
public class JwtTokenProvider {
    // Access Token: 2 小时
    public String generateAccessToken(User user) {
        Date expiry = new Date(System.currentTimeMillis() + 2 * 60 * 60 * 1000);
        return Jwts.builder()
                .setSubject(user.getUserId().toString())
                .claim("username", user.getUsername())
                .setIssuedAt(new Date())
                .setExpiration(expiry)
                .signWith(getSecretKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    // Refresh Token: 7 天
    public String generateRefreshToken(User user) {
        Date expiry = new Date(System.currentTimeMillis() + 7 * 24 * 60 * 60 * 1000);
        return Jwts.builder()
                .setSubject(user.getUserId().toString())
                .setIssuedAt(new Date())
                .setExpiration(expiry)
                .signWith(getRefreshSecretKey(), SignatureAlgorithm.HS256)
                .compact();
    }
}
```

### 权限设计
| 权限码 | 说明 | 资源类型 |
|--------|------|----------|
| ORDER:PLACE | 下单 | ORDER |
| ORDER:CANCEL | 撤单 | ORDER |
| ORDER:QUERY | 查单 | ORDER |
| ACCOUNT:QUERY | 查询余额 | ACCOUNT |
| ACCOUNT:DEPOSIT | 充值 | ACCOUNT |
| ACCOUNT:WITHDRAW | 提现 | ACCOUNT |
| ACCOUNT:TRANSFER | 转账 | ACCOUNT |
| TRADE:QUERY | 查询交易记录 | TRADE |

---

## 七、限流与风控

### 登录限流
```yaml
# 用户服务限流配置
rate-limit:
  # 登录限流
  login:
    max-attempts: 5          # 最大尝试次数
    lock-minutes: 15          # 锁定时间（分钟）
    per-minute: 10           # 每分钟最大次数

  # 注册限流
  register:
    per-ip: 3               # 每IP每天最大注册数
    per-email: 5             # 每邮箱每天最大注册数

  # API 调用限流
  api:
    requests-per-second: 100
    burst-capacity: 200
```

### 密码安全
```java
public class PasswordService {
    // 使用 BCrypt 加密
    public String hashPassword(String password) {
        return BCrypt.hashpw(password, BCrypt.gensalt());
    }

    // 验证密码
    public boolean checkPassword(String password, String hash) {
        return BCrypt.checkpw(password, hash);
    }

    // 密码强度验证
    public boolean isStrongPassword(String password) {
        // 至少 8 位
        // 包含大小写字母
        // 包含数字
        // 包含特殊字符
        return password.length() >= 8 &&
               password.matches(".*[A-Z].*") &&
               password.matches(".*[a-z].*") &&
               password.matches(".*\\d.*") &&
               password.matches(".*[!@#$%^&*].*");
    }
}
```

---

## 八、服务间通信

### 撮合引擎调用用户服务
```java
@FeignClient(name = "user-service", url = "http://user-service:8083")
public interface UserClient {
    @GetMapping("/api/user/info")
    UserInfoResponse getUserInfo(@RequestParam Long userId);

    @PostMapping("/api/user/verify")
    VerifyResponse verifyToken(@RequestHeader("Authorization") String token);
}
```

### 用户服务调用账务服务
```java
@FeignClient(name = "account-service", url = "http://account-service:8081")
public interface AccountClient {
    @GetMapping("/api/account/balance")
    BalanceResponse getBalance(@RequestParam Long userId, @RequestParam String currency);
}
```

---

## 九、监控指标

| 指标 | 说明 | 告警阈值 |
|--------|------|----------|
| user_register_total | 注册总数 | N/A |
| user_login_total | 登录总数 | N/A |
| user_login_fail_total | 登录失败总数 | > 100/min |
| user_active_total | 活跃用户数 | N/A |
| token_issue_total | Token 签发总数 | N/A |
| token_verify_total | Token 验证总数 | > 10000/min |
| api_latency | API 延迟 | P99 > 100ms |

---

## 十、OAuth 2.0 / 第三方登录

### 支持的第三方登录
| 平台 | 说明 |
|------|------|
| Google | OAuth 2.0 |
| GitHub | OAuth 2.0 |
| Apple | Sign in with Apple |
| 邮箱 | 邮箱验证登录 |

### OAuth 流程
```
1. 用户点击第三方登录按钮
   ↓
2. 重定向到第三方平台
   ↓
3. 用户授权
   ↓
4. 第三方平台回调，携带 code
   ↓
5. 后端用 code 换取 access_token
   ↓
6. 获取用户信息
   ↓
7. 查找/创建本地用户
   ↓
8. 生成 JWT Token
   ↓
9. 返回给前端
```

---

## 十一、部署架构

```
┌────────────────────────────────────────────────────────────┐
│              Nginx / API Gateway               │
├────────────────────────────────────────────────────────────┤
│                                                         │
│  ┌─────────────────────────────────────────────┐        │
│  │           负载均衡器             │        │
│  │ ┌────────┬────────┬────────┬────────┐ │        │
│  │ │ Match │ Acct  │ Push  │ User   │ │        │
│  │ │ 8080  │ 8081  │ 8082  │ 8083  │ │        │
│  │ └────────┴────────┴────────┴────────┘ │        │
│  └─────────────────────────────────────────────┘        │
└────────────────────────────────────────────────────────────┘
```

### Nginx 配置
```nginx
upstream user_backend {
    server user1:8083;
    server user2:8083;

    check interval=3000 rise=2 fall=3 timeout=1000;
}

server {
    listen 8083 ssl;
    server_name user.example.com;

    location /api/user/ {
        proxy_pass http://user_backend;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;

        # 限流
        limit_req_zone $binary_remote_addr zone=login:10m rate=5r/s burst=10;
        limit_req zone=login nodelay;
        limit_req_status 429;
    }
}
```
