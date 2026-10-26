# 架构设计

> 本文描述 Tollgate V1.3 的真实架构。所有类名均可直接在代码库中定位到。

## 1. 顶层结构：控制面 / 数据面分离

```
客户端（OpenAI SDK）
    │  POST /v1/chat/completions（虚拟密钥 tg-xxx）
    ▼
┌─────────────────── 数据面（热路径）───────────────────┐
│ DataPlaneRouter → ChatCompletionsHandler              │
│   鉴权 → 限流 → 选路 → 转发 → 计量                      │
│         │                                             │
│   LaneSelector（tollgate-core，纯 Java 无框架依赖）     │
│   CatalogSnapshot（内存快照，热路径零查库）              │
└────────┬──────────────────────────────┬───────────────┘
         │ 转发                          │ 攒批落账
         ▼                              ▼
   上游（OpenAI/DeepSeek/…）      MySQL（toll_record）
                                        ▲
┌─────────────── 控制面（管护路径）────────┴──────────────┐
│ AdminRouter /AdminController（/admin 前缀）             │
│ 车道 CRUD · 路由配置 · 密钥签发吊销 · 快照刷新 · 熔断观测 │
│ JDBC 全部跑在 boundedElastic / 虚拟线程，不占事件循环    │
└───────────────────────────────────────────────────────┘
```

两平面唯一的耦合点是 **CatalogSnapshot（不可变目录快照）**：

- 控制面写 MySQL，写完调 `POST /admin/refresh` 触发重载（也可等定时刷新）；
- `CatalogLoader` 从库里拉全量配置，构建新的不可变快照，原子替换；
- `DispatchRuntime` 监听快照变化（`CatalogLoader.onRefresh` 监听器），重建限流器与密钥池；
- 数据面每请求只读快照，**热路径零 SQL**。

## 2. Maven 模块与依赖边界

```
tollgate-common    领域模型、错误码、TokenUsage
    ▲
tollgate-upstream  UpstreamAdapter SPI + OpenAI 兼容适配器
    ▲
tollgate-core      调度内核（限流/选路/熔断/密钥池）——纯 Java，可脱离 Spring 单测
    ▲
tollgate-metering  TollRecord / TollRecorder 接口
    ▲
tollgate-app       Spring Boot 装配：数据面入口、控制面 API、Flyway 迁移
```

依赖方向单一向下，`tollgate-core` 是被保护的核心——38 个单元测试里大部分落在这个模块，不依赖容器即可运行。

## 3. 一次请求的完整生命周期

以 `ChatCompletionsHandler.chatCompletions` 为准：

```
1. Auth       解析 Authorization: Bearer tg-xxx → SHA-256 → 查快照定位租户
              （api_key 明文不落库，只存 hash；前缀 key_prefix 用于日志定位）
2. Estimate   TokenEstimator 按请求体字符数预估 tokens（TPM 限流用）
3. Select     LaneSelector.select(tenantId, model, estimatedTokens)
              ├─ 限流：RPM/TPM 双维度令牌桶，超限 429（token 不足时退还请求额度）
              ├─ 候选：CandidateResolver 按优先级分层，层内 SWRR 平滑轮询
              ├─ 密钥：KeyPool 原子游标轮换，被禁密钥跳过
              └─ 熔断：tryAcquire 失败换下一车道；全灭 → 503 NO_AVAILABLE_LANE
4. Forward    OpenAiCompatibleAdapter
              ├─ 报文透传：只改 model（别名→上游名）与 stream，其余参数原样透传
              ├─ 非流式：聚合响应，解析 usage
              └─ 流式：按 Content-Type 分流解码，逐帧嗅探 usage
5. Failover   失败且可重试 → selection.fail()（喂熔断器/密钥池）→ 回到 3 换道
              最多 dispatch.max-attempts 次
6. Toll       BatchingTollRecorder 入队 → 单线程攒批（200 条 / 2s）落 toll_record
7. Log        request_log 每 attempt 一行，(trace_id, attempt) 可还原换道全程
```

关键顺序是**先取密钥再过熔断**（见 [design-decisions.md](design-decisions.md) §3）。

## 4. 调度内核四个组件

| 组件 | 文件 | 职责 | 并发手段 |
|---|---|---|---|
| `CandidateResolver` | `core/dispatch/CandidateResolver.java` | 模型别名 → 车道候选集：按 priority 升序分层，层内按 weight SWRR；用 `(id, weight)` 签名检测配置变更，变更时重建 selector | per-(model, priority) 缓存 |
| `SmoothWeightedSelector` | `core/dispatch/SmoothWeightedSelector.java` | Nginx SWRR 平滑加权轮询，权重 3:1 时输出 A A A B 而非 A A A A B | `currentWeight` 原子操作 |
| `LaneCircuitBreaker` | `core/breaker/LaneCircuitBreaker.java` | 三态状态机 CLOSED/OPEN/HALF_OPEN，全 CAS 无锁 | `AtomicReference` + `AtomicInteger.tryAcquire`（半开配额） |
| `SlidingWindow` | `core/breaker/SlidingWindow.java` | 环形分桶滑动窗口（1 秒 1 桶），成功/失败分桶计数 | `LongAdder` 数组 |
| `KeyPool` | `core/keypool/KeyPool.java` | 车道内密钥轮换 + 连续失败封禁（ban-threshold 次）+ 到期解封 | `AtomicInteger` 游标 |
| `TenantRateLimiter` | `core/ratelimit/TenantRateLimiter.java` | RPM/TPM 双维度令牌桶，RPM 拒绝时不动 TPM，TPM 不足时退还已扣的 RPM | `AtomicLong` 水位 |

分层防御的语义差异：

- **密钥池封禁**是细粒度、强信号的——密钥失效是持续状态（401/403 不会自己好），连续 3 次失败即封；
- **熔断器**是车道粒度、统计信号的——需要窗口内样本（minimum-calls）达到失败率阈值才打开。

两者触发速度不同：密钥失效场景下密钥池封禁会先于熔断器生效，所以实际观察到的 503 可能来自 KeyPool 而非熔断器，排查时看 `/admin/breakers` 与密钥状态。

## 5. 快照机制（热路径零查库的关键）

```
CatalogSnapshot（不可变）
├─ lanes:        Map<laneId, Lane>
├─ routes:       Map<modelAlias, List<ModelRoute>>   // 调度候选来源
├─ credentials:  Map<keyHash, TenantCredential>      // 鉴权
├─ laneSecrets:  Map<laneId, List<String>>           // 密钥池原料
└─ rateLimits:   Map<tenantId, RateLimitRule>        // RPM/TPM
```

- 快照构建后不可变，数据面拿到的是引用，无锁读；
- 刷新是**整体替换**而非原地修改，不存在读到半新半旧配置的窗口；
- `DispatchRuntime.onCatalogRefreshed` 在新快照生效时重建限流器——否则限流阈值会冻结在启动时刻的值（V1.3 验收抓出的真实缺陷）。

## 6. SSE 流式处理（数据面最难的三十行）

上游响应按 `Content-Type` 分流：

- `text/event-stream`：WebClient 会走 SSE 解码器，`bodyToFlux(String)` 拿到的是**已剥掉 `data:` 前缀的裸数据**——必须用 `ServerSentEvent<String>` 类型接收，再按行找不到前缀会全部落空（流只剩 `[DONE]`）；
- 非 SSE Content-Type（部分兼容上游用 `text/plain`）：逐行切分，`splitSseLines` 手工剥 `data:` 前缀。

usage 计量三原则：

1. 显式携带 `stream_options.include_usage=true` 索要 usage 帧（不认识该字段的上游忽略，无副作用）；
2. `usageFrameEmitted` 标记去重——usage 帧 + `[DONE]` 终端帧可能各带一次；
3. 没等到真实 usage 时按字符估算（4 字符 ≈ 1 token），`estimated=true` 落库，宁可估算不丢账。

## 7. 技术栈与运行形态

| 层 | 选型 | 说明 |
|---|---|---|
| 运行时 | Java 21，虚拟线程开启 | 控制面 JDBC 与落账阻塞调用跑在虚拟线程上 |
| Web | Spring WebFlux (Reactor Netty) | 数据面全异步非阻塞 |
| 路由 | RouterFunction（函数式端点） | 数据面/控制面各一个 Router Bean，互不干扰 |
| 存储 | MySQL 8 + Flyway | 9 张表，V1 建表 + V2 种子数据 |
| 计量落账 | 内存攒批 + 单线程刷库 | 批量 200 条或 2 秒强制刷盘 |

明确**不引入**的东西：R2DBC（JDBC+虚拟线程已够）、Resilience4j（阻塞模型，不匹配响应式链路）、Guava RateLimiter（单维度+阻塞模型）。理由见 [design-decisions.md](design-decisions.md)。

## 8. 代码导读索引

想按"从外到内"读代码，建议顺序：

1. `app/dataplane/DataPlaneRouter` → 对外契约只有一条路由
2. `app/dataplane/ChatCompletionsHandler` → 主流程编排（本文 §3）
3. `core/dispatch/LaneSelector` → 调度统一入口
4. `core/dispatch/CandidateResolver` + `SmoothWeightedSelector` → 选路算法
5. `core/breaker/LaneCircuitBreaker` + `SlidingWindow` → 熔断器
6. `core/keypool/KeyPool` → 密钥池
7. `upstream/openai/OpenAiCompatibleAdapter` → 上游适配（含 SSE 处理）
8. `app/metering/BatchingTollRecorder` → 攒批落账
9. `app/controlplane/AdminController` → 管护通道
