# 运行手册

## 环境要求

| 依赖 | 版本 | 说明 |
|---|---|---|
| JDK | 21+ | 虚拟线程依赖 |
| Maven | 3.9.x | `~/.m2/wrapper/dists` 内自带 3.9.9 可用 |
| MySQL | 8.0.16+ | CHECK 约束强制执行需要 8.0.16+ |
| Python | 3.x | 仅本地 mock 上游需要（`http.server` 标准库实现） |

Redis 在 V1 尚未实际启用，配置位已预留。

## 1. 准备数据库

复用已有 MySQL 实例时：

```sql
CREATE DATABASE tollgate DEFAULT CHARSET utf8mb4;
CREATE USER 'tollgate'@'%' IDENTIFIED BY 'tollgate';
GRANT ALL ON tollgate.* TO 'tollgate'@'%';
```

建表不用手工执行——Flyway 启动时自动跑 `V1__init.sql`（建表）与 `V2__seed_demo.sql`（演示数据）。

## 2. 构建与启动

```bash
# 构建（跳过测试可加 -DskipTests，但建议全跑：38 个单测是回归底线）
./mvnw clean package

# 启动（显式指定端口，避免环境变量注入干扰）
java -jar tollgate-app/target/tollgate-app-*.jar --server.port=18080
```

连接参数全部支持环境变量覆盖：

| 变量 | 默认值 |
|---|---|
| `DB_HOST` / `DB_PORT` / `DB_NAME` | localhost / 3306 / tollgate |
| `DB_USER` / `DB_PASSWORD` | tollgate / tollgate |

健康检查：`curl http://localhost:18080/actuator/health` → `{"status":"UP"}`

## 3. 启动本地 mock 上游

```bash
python3 scripts/mock_openai_upstream.py   # 监听 9999，支持流式/非流式/usage 帧
```

演示数据里 `lane-relay` 指向一个不存在的地址，是**故意保留**的故障转移演示位：SWRR 会按 1/(3+1) 的概率把请求路由过去失败一次，attempt=2 自动换道 lane-openai 成功——`/admin/recent-requests` 可看到完整换道过程。

## 4. 配置项（application.yml → `tollgate.*`）

```yaml
tollgate:
  dispatch:
    max-attempts: 3          # 单次请求最多尝试的车道数（含首次）
    request-timeout: 120s    # 单车道调用超时
    first-token-timeout: 30s # 流式首包超时（独立于总超时）
  breaker:
    failure-rate-threshold: 0.5
    minimum-calls: 10        # 窗口最小样本数
    open-duration-seconds: 30
    half-open-permits: 3
    window-seconds: 10       # 同时是分桶数（1 秒 1 桶）
  keypool:
    ban-threshold: 3         # 连续失败封禁阈值
    ban-duration-seconds: 60
  metering:
    batch-size: 200          # 攒批条数阈值
    flush-interval: 2s       # 强制刷盘间隔
```

**演示熔断时调参**：`minimum-calls: 2`、`window-seconds: 60`、`ban-threshold: 100`（排除密钥池封禁干扰，纯看熔断状态机）。

## 5. 验证清单

### 基础转发

```bash
curl -s http://localhost:18080/v1/chat/completions \
  -H "Authorization: Bearer tg-demo0000000000" \
  -H "Content-Type: application/json" \
  -d '{"model":"gpt-4o-mini","messages":[{"role":"user","content":"你好"}]}'
```

### 流式 SSE

```bash
curl -sN http://localhost:18080/v1/chat/completions \
  -H "Authorization: Bearer tg-demo0000000000" \
  -H "Content-Type: application/json" \
  -d '{"model":"gpt-4o-mini","messages":[{"role":"user","content":"讲个故事"}],"stream":true}'
```

### 计量落库验证

```sql
SELECT trace_id, attempt, model_alias, lane_code,
       prompt_tokens, completion_tokens, estimated, success, latency_millis
FROM toll_record ORDER BY id DESC LIMIT 10;
```

### 限流 429

把 `demo` 租户 `rpm_limit` 改为 3 → `POST /admin/refresh` → 连发 6 个请求应精确得到 3×200 + 3×429 → 恢复配置再 refresh。

### 熔断三态演示

1. 停掉 mock 上游 → 连发请求直到 `/admin/breakers` 显示 `OPEN`
2. 冷却期内再请求 → 快速失败（不真正打上游）
3. 重启 mock → 等冷却结束 → 发请求探测 → `/admin/breakers` 回到 `CLOSED`，`tripCount=1` 留痕

### 换道重试

```bash
curl -s http://localhost:18080/admin/recent-requests
# 找 lane_code=lane-relay、status=FAILED 的 attempt=1 行，
# 其后同 trace_id 必有 attempt=2 换道成功的行
```

## 6. 接真实上游（以 DeepSeek 为例）

代码零改动——`OPENAI_COMPATIBLE` 适配器覆盖 OpenAI/DeepSeek/Moonshot 及各类中转站：

```bash
curl -X POST http://localhost:18080/admin/lanes -H "Content-Type: application/json" -d '{
  "code": "lane-deepseek", "providerCode": "deepseek",
  "baseUrl": "https://api.deepseek.com", "priority": 20,
  "upstreamKeys": ["sk-真实密钥"]
}'
curl -X PUT http://localhost:18080/admin/routes -H "Content-Type: application/json" -d '{
  "modelAlias": "deepseek-chat", "laneId": <上一步返回的id>, "upstreamModel": "deepseek-chat"
}'
curl -X POST http://localhost:18080/admin/refresh
```

> 种子数据中 DeepSeek 密钥是占位符，接真实上游前需替换。

## 7. 常见问题

| 现象 | 原因 | 处理 |
|---|---|---|
| 401 | 虚拟密钥错误/吊销，或 Bearer 头缺失 | `GET /admin/api-keys` 核对前缀 |
| 404 MODEL_NOT_FOUND | 别名未注册或无 enabled 路由 | `GET /admin/routes` 检查 |
| 503 | 候选车道全灭（密钥全禁或熔断全开） | `/admin/breakers` + `/admin/lanes` 看状态 |
| 配置改了不生效 | 数据面读快照 | `POST /admin/refresh` |
| 429 之后一直 429 | 令牌桶按时间恢复，或阈值仍低 | 核对 tenant 配置后 refresh |
| 熔断演示无反应 | 样本滑出窗口 | 演示参数调大 window-seconds |

## 8. 单元测试

```bash
./mvnw test        # 全量；core 模块不依赖容器可单独跑：
./mvnw -pl tollgate-core test
```

当前 38 个测试全绿，覆盖 SWRR 交错性、熔断三态迁移、半开配额、密钥封禁/解封、双维度限流退还语义。
