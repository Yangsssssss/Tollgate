# API 参考

## 数据面（OpenAI 兼容协议）

### POST /v1/chat/completions

请求与响应报文兼容 OpenAI Chat Completions，客户端用任意 OpenAI SDK 直连即可。

**鉴权**：`Authorization: Bearer <虚拟密钥>`（形如 `tg-xxxxxxxx`，控制面签发）。

```bash
# 非流式
curl http://localhost:18080/v1/chat/completions \
  -H "Authorization: Bearer tg-demo0000000000" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gpt-4o-mini",
    "messages": [{"role": "user", "content": "你好"}]
  }'

# 流式（SSE）
curl -N http://localhost:18080/v1/chat/completions \
  -H "Authorization: Bearer tg-demo0000000000" \
  -H "Content-Type: application/json" \
  -d '{"model": "gpt-4o-mini", "messages": [...], "stream": true}'
```

**透传语义**：请求体不做结构化重建，只在 JSON 树上改两个字段——`model` 换成上游模型名、`stream` 按需强设。`response_format`、`tools`、`seed` 等厂商特有参数原样到达上游。

**错误响应**统一为 OpenAI 风格：

```json
{"error": {"message": "...", "type": "...", "code": "TENANT_RATE_LIMITED"}}
```

### 错误码表

| ErrorCode | HTTP | 语义 |
|---|---|---|
| `INVALID_REQUEST` | 400 | 请求体不合法 / 参数错误（不重试） |
| `UNAUTHORIZED` | 401 | 虚拟密钥无效或已吊销 |
| `MODEL_NOT_FOUND` | 404 | 模型别名未注册或未配置任何路由 |
| `TENANT_RATE_LIMITED` | 429 | RPM 超限 |
| `TENANT_TOKEN_LIMITED` | 429 | TPM 超限 |
| `NO_AVAILABLE_LANE` | 503 | 候选车道全灭（密钥全禁 / 熔断全开） |
| `LANE_OVERLOADED` | —（内部） | 上游 429，触发换道 |
| `UPSTREAM_TIMEOUT` | —（内部） | 上游超时/5xx，触发换道 |

重试语义：429 / 401 / 403 / 5xx 视为可重试（换车道），4xx 参数类错误直接透出。

## 控制面（/admin 前缀）

管护通道，调用方是人和脚本；参数缺失/校验失败 → 400，未捕获异常 → 500。

### 车道管理

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/admin/lanes` | 列出全部车道，附每条车道的熔断器状态视图 |
| POST | `/admin/lanes` | 创建车道。必填 `code` / `providerCode` / `baseUrl`；可选 `priority`(默认100) / `weight`(默认1) / `timeoutMillis` / `upstreamKeys`(数组) |
| PATCH | `/admin/lanes/{id}` | 局部更新：`baseUrl` / `priority` / `weight` / `status`(ENABLED\|DISABLED) |

熔断器状态视图字段：`state`（CLOSED/OPEN/HALF_OPEN/NEVER_USED）、`failureRate`、`sampleCount`、`tripCount`、`rejectedCount`、`remainingOpenMillis`。

### 路由（模型 × 车道）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/admin/routes` | 列出全部路由映射 |
| PUT | `/admin/routes` | upsert。必填 `modelAlias` / `laneId` / `upstreamModel`；可选 `weightOverride`（null 继承车道权重） |
| DELETE | `/admin/routes/{alias}/{laneId}` | 下线路由（enabled=false） |

### 虚拟密钥（对外）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/admin/api-keys` | 列出密钥（只含前缀，无明文） |
| POST | `/admin/api-keys` | 签发。必填 `tenantCode` / `name`；响应中 `key` 明文**仅此一次展示** |
| DELETE | `/admin/api-keys/{id}` | 吊销（最迟一个刷新周期后失效） |

签发响应示例：

```json
{"id": 2, "key": "tg-a1b2c3d4e5f6...", "prefix": "tg-a1b2c3d4",
 "warning": "明文仅此一次展示，请立即保存"}
```

### 运维观测

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/admin/refresh` | 手动重载目录快照。返回 lanes/models/credentials/laneKeys 数量 |
| GET | `/admin/breakers` | 全部熔断器实时状态 |
| GET | `/admin/recent-requests` | 最近调用明细（含换道过程） |

> 所有写操作完成后都提示"等待快照刷新后生效"——数据面读的是内存快照，写库不等于生效，需调 `POST /admin/refresh` 或等定时刷新。

## 健康检查

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/actuator/health` | 存活探针 |
| GET | `/actuator/metrics`、`/actuator/prometheus` | 指标 |

## 典型运维流程

### 新接一个上游渠道

```bash
# 1. 建车道（含初始密钥）
curl -X POST http://localhost:18080/admin/lanes -H "Content-Type: application/json" -d '{
  "code": "lane-deepseek", "providerCode": "deepseek",
  "baseUrl": "https://api.deepseek.com",
  "priority": 20, "weight": 1,
  "upstreamKeys": ["sk-真实密钥"]
}'

# 2. 配路由：模型别名 → 车道 + 上游真实模型名
curl -X PUT http://localhost:18080/admin/routes -H "Content-Type: application/json" -d '{
  "modelAlias": "deepseek-chat", "laneId": 4, "upstreamModel": "deepseek-chat"
}'

# 3. 刷新快照生效
curl -X POST http://localhost:18080/admin/refresh

# 4. 验证
curl http://localhost:18080/v1/chat/completions \
  -H "Authorization: Bearer tg-demo0000000000" \
  -H "Content-Type: application/json" \
  -d '{"model": "deepseek-chat", "messages": [{"role":"user","content":"hi"}]}'
```

### 租户接入

```bash
# 签发密钥（明文只出现一次）
curl -X POST http://localhost:18080/admin/api-keys \
  -H "Content-Type: application/json" \
  -d '{"tenantCode": "demo", "name": "测试接入"}'
```
