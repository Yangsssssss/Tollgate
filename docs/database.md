# 数据模型

> 以 `tollgate-app/src/main/resources/db/migration/V1__init.sql` 为准（MySQL 8.0.16+，CHECK 约束强制执行）。

## 全局约定

- 主键 `BIGINT AUTO_INCREMENT`，不在应用层生成
- 时间 `DATETIME(3)`，应用层统一写 UTC；`updated_at` 交给 `ON UPDATE` 自动维护
- 金额/token 全整数，杜绝浮点误差
- **外键取舍**：配置表（低频写）保留 FK 保完整性；日志与计量表（高频写）不加 FK 换写入吞吐，靠应用层保证引用有效

## ER 关系

```
tenant 1──N api_key
tenant 1──N request_log / toll_record
provider 1──N lane 1──N lane_key
model N──N lane (通过 model_lane，含 upstream_model 与 weight_override)
```

## 表清单

### 配置面（控制面写，数据面经快照读）

| 表 | 职责 | 关键约束 |
|---|---|---|
| `tenant` | 租户，限流与账单的顶层维度 | `uk_tenant_code`；`rpm_limit` / `tpm_limit` |
| `api_key` | 对外虚拟密钥 | `uk_api_key_hash`：只存 SHA-256（CHAR(64) hex 定长）；`key_prefix` 形如 `tg-ab12cd34` 供日志定位；明文创建时返回一次 |
| `provider` | 上游厂商，`protocol` 决定路由到哪个 UpstreamAdapter | CHECK 限 `OPENAI_COMPATIBLE` / `ANTHROPIC` / `GEMINI` |
| `lane` | 车道 = 厂商账号 + Base URL + 密钥池 + 权重 + 优先级 | `priority` 小者优先；`weight` ≥ 0（0 表示该车道暂不参与同层轮询） |
| `lane_key` | 车道密钥池 | `uk_lane_key (lane_id, secret)`；状态 ACTIVE/COOLDOWN/DISABLED。运行时冷却状态以内存为准，DB 只是持久化副本 |
| `model` | 对外模型别名与元信息 | 客户端只见 alias，真实上游名在 model_lane |
| `model_lane` | 模型 × 车道映射，调度候选集直接来源 | `uk_model_lane (model_id, lane_id)`；`weight_override` NULL 继承车道权重。MySQL 无部分索引，退化为 `(model_id, enabled)` 联合索引 |

### 运行面（高频写，无 FK 无 CHECK）

| 表 | 职责 | 关键设计 |
|---|---|---|
| `request_log` | 调用明细，**每个 attempt 一行** | `trace_id` 标识客户端一次完整请求；按 `(trace_id, attempt)` 可还原换道全程；索引 `(tenant_id, created_at DESC)`、`(lane_code, created_at DESC)` |
| `toll_record` | 计量明细，计费唯一凭据 | 幂等键 `uk_toll_idem (trace_id, attempt)`；`estimated` 标记 usage 来源（真实/估算）；`cost_micros` 单位百万分之一元，按落账当时费率快照计算，价格调整不回溯 |

## 幂等落账

`toll_record` 以 `(trace_id, attempt)` 为唯一键，落账 SQL 走：

```sql
INSERT ... ON DUPLICATE KEY UPDATE id = id
```

命中唯一键时做一次无操作更新——重试或投递重放时同一逻辑请求的同一 attempt 只记一次账。

> 不用 `INSERT IGNORE`：它会顺带吞掉截断、非空违约等真实错误，把数据质量问题静默化。

## 密钥安全现状

- 对外（api_key）：明文不落库，SHA-256 哈希查找，前缀用于定位。
- 对上游（lane_key）：V1 明文存储，仅限本地演示。TODO(V2)：应用层信封加密（KMS 主密钥 + 数据密钥）。
- 运行时防泄漏：`KeyPool.LaneKey` 覆写 `toString()`，日志/异常栈不会带出 secret。

## 种子数据（V2__seed_demo.sql）

| 项 | 值 |
|---|---|
| 厂商 | openai / anthropic / deepseek（前两者与后者均为 OPENAI_COMPATIBLE） |
| 车道 | lane-openai（p10,w3）、lane-relay（p10,w1，故意保留用于故障转移演示）、lane-deepseek（p20,w1） |
| 模型 | gpt-4o-mini（双车道，调度生效前提）、deepseek-chat |
| 租户 | demo（RPM 120 / TPM 200000） |
| 演示密钥 | 明文 `tg-demo0000000000`，库内 hash `2be2e9a5457f85f0...` |

幂等策略：`ON DUPLICATE KEY UPDATE id = id`（命中唯一键做无操作更新），刻意不用 `INSERT IGNORE`（理由同上）。
