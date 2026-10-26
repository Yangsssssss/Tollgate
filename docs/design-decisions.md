# 关键设计与踩坑实录

> 这是本项目最值得读的一篇。每个决策都有代价；每个坑都是真实发生、被测试或验收抓出来后修掉的。面试深挖时按此逐条讨论。

## 一、设计决策

### 1. 热路径零查库：不可变快照 + 整体替换

调度需要的全部数据（车道、路由、密钥、限流规则）驻留内存快照，请求链路零 SQL。

- **代价**：配置变更存在一致性窗口（写库后未刷新前不生效）。
- **缓解**：控制面写完可手动 `POST /admin/refresh` 立即生效；另有定时兜底刷新。
- **关键细节**：快照不可变 + 整体替换，不存在读到"半新半旧"配置的窗口。
- **踩过的坑**：快照刷新后限流器没跟着重建（`onCatalogRefreshed` 无人调用），限流阈值冻结在启动时刻——已通过 `CatalogLoader` 刷新监听器接线修复。教训：**响应式系统里"配置变了"和"运行时状态跟着变"是两件事，中间的接线必须显式验证**。

### 2. 手写熔断器，不用 Resilience4j

Resilience4j 基于信号量/线程池，本质阻塞模型，放进 WebFlux 链路会占事件循环。自研三态状态机：

- 状态迁移全 CAS（`AtomicReference`），半开探测配额用 `AtomicInteger.tryAcquire` 严格限 N 个；
- 滑动窗口用环形分桶（1 秒 1 桶）+ `LongAdder`，避免单锁争抢；
- **熔断器只在 onFailure 评估会漏判**：2 失败 + 2 成功、阈值 50% 时样本已达标却不触发——成功时也必须评估（单元测试抓出的真 bug）。

### 3. 调度顺序：先取密钥，再过熔断

`LaneSelector.select` 的顺序是 限流 → 候选 → **密钥** → **熔断**。若反过来（先熔断再取密钥）有个隐蔽泄漏：车道处于半开，`tryAcquire` 消耗了探测配额，但密钥池恰好全禁返回 null——这个探测名额既没被使用也没被归还，**半开配额被永久占用，熔断器再也回不到 CLOSED**。先拿密钥可保证：只要走过密钥这关，熔断拒绝是无副作用的。

### 4. 分层防御：密钥池封禁 ≠ 熔断器

| 层 | 粒度 | 信号 | 阈值 |
|---|---|---|---|
| KeyPool 封禁 | 单个密钥 | 连续失败（401/403 是持续状态，不会自愈） | 连续 3 次 |
| 熔断器 | 整条车道 | 窗口失败率（偶发抖动的统计信号） | ≥10 样本且失败率 ≥50% |

两者触发速度不同：密钥失效场景下封禁先于熔断生效，所以 503 快速失败可能来自 KeyPool 而非熔断器——观测时要分清。

### 5. SSE 按 Content-Type 分流解码

上游返回 `text/event-stream` 时，WebClient 的 `bodyToFlux(String)` 会走 SSE 解码器，返回的是**已剥掉 `data:` 前缀的裸数据**；此时适配器再按行找 `data:` 前缀会全部落空，流只剩 `[DONE]`。修复：SSE Content-Type 用 `ServerSentEvent<String>` 接收；非标上游（如 `text/plain`）才走逐行切分。

这是本项目最有面试价值的一个坑：**同一个方法名，解码行为取决于 Content-Type**。

### 6. usage 计量：宁可估算，不丢账

- 流式请求显式带 `stream_options.include_usage=true` 索要 usage 帧；
- `usageFrameEmitted` 标记去重（usage 帧 + `[DONE]` 终端帧可能各带一次）；
- 没等到真实 usage 时按字符估算（4 字符 ≈ 1 token），`estimated=true` 落库。
- **幂等**：`(trace_id, attempt)` 唯一键 + `ON DUPLICATE KEY UPDATE id=id`。不做精确一次，做至少一次 + 幂等去重——计费场景对漏记的容忍度低于重记。

### 7. 报文透传优先

请求体不做结构化重建，只改 `model`（别名→上游名）和 `stream`。厂商特有参数原样到达上游，适配器不需要追着参数表跑。代价是网关无法拦截不认识的危险参数——当前场景下收益大于风险。

### 8. 不追求纯响应式

数据面 WebFlux 全异步；控制面与落账走 JDBC + Java 21 虚拟线程，不引入 R2DBC。阻塞调用在虚拟线程上发生不会拖累事件循环——这是 Java 21 给的正解，不为纯度牺牲可维护性。

### 9. KeyPool 空池是合法状态

车道未配密钥 ≠ 配置错误。原实现拒绝空列表，把合法配置状态变成运行期故障；改为 `acquire()` 返回 null，调度器跳过该车道。另外 `LaneKey` 覆写 `toString()` 防 secret 泄漏到日志。

### 10. TPM 令牌桶的细节

RPM/TPM 双维度令牌桶，不用 Guava（单维度 + 阻塞模型）。细节：token 预算不足时**退还已扣的请求额度**，否则 TPM 大请求会白白消耗 RPM 配额。

## 二、踩坑实录（按发生顺序）

| # | 坑 | 现象 | 修复 |
|---|---|---|---|
| 1 | Maven settings 指向前公司内网 Nexus | 依赖拉取失败，且内含明文凭据 | 换阿里云镜像，旧配置备份 |
| 2 | Connector/J `characterEncoding` 写了 `utf8mb4` | 启动即挂——这是 MySQL 字符集名，不是 Java 字符集名 | 改 `UTF-8` |
| 3 | `mvnw spring-boot:run` 在多模块父 POM 上执行 | 在父 POM 上找主类报错 | package + `java -jar` |
| 4 | 本机端口 IPv4 被其他进程占用，java 绑 IPv6 | curl 127.0.0.1 打到别的进程返回 AUTH_REQUIRED，误判应用没起 | 用 `[::1]` 或显式 `--server.port` |
| 5 | SSE 解码陷阱 | 流式响应只剩 `[DONE]`，内容帧全丢 | 按 Content-Type 分流（见 §5） |
| 6 | `@ConditionalOnBean(DataSource.class)` 放在用户 `@Component` 上 | 条件永不成立——用户 bean 的条件评估早于自动配置 | 用户组件不要用 ConditionalOnBean 依赖自动配置 bean |
| 7 | usage 帧重复 | 计量重复入账 | `usageFrameEmitted` 标记去重 |
| 8 | SWRR 测试断言错误 | 5:1 权重出现 5 连 A | 不是 bug——跨周期拼接固有现象；改用三节点验证交错性 |
| 9 | 熔断只在 onFailure 评估 | 样本达标不熔断 | onSuccess 也评估 |
| 10 | KeyPool 拒绝空列表 | 未配密钥的车道变运行期故障 | acquire 返回 null，调度跳过 |
| 11 | MySQL 无 `INSERT ... RETURNING` | 拿不到自增主键 | 改 `GeneratedKeyHolder` |
| 12 | 限流阈值冻结 | 运行时改 RPM 不生效 | 刷新监听器重建限流器 |
| 13 | 熔断器记不上连接类故障 | DNS/连接失败漏过 `selection.fail()`，熔断器无感知 | 异常处理接所有异常并包装为 retryable |
| 14 | 裸 500 泄漏内部堆栈 | 上游异常直接透出 | 顶层兜底统一转 OpenAI 风格错误报文 |

## 三、验证实录（可复现）

三项验收均以真实进程 + curl 完成（mock 上游 9999，网关 18080）：

- **429**：RPM 限到 3，8 个请求精确命中 `3×200 + 3×429`；恢复配置经 `/admin/refresh` 后全 200
- **控制面全流程**：签发密钥（明文一次）→ 配路由 → 调用成功 → 吊销 → 401
- **熔断三态**：CLOSED →（2 样本 100% 失败）→ OPEN → 冷却期快速失败拒绝 2 次 → mock 恢复后探测成功 → CLOSED（窗口重置、tripCount=1 留痕）
- **故障转移**：8 请求压测，SWRR 恰把 1 个请求路由到故意留的坏车道，request_log 记 `FAILED(upstream_timeout)`，attempt=2 换道成功

> 观察滑动窗口计数要在 `window-seconds` 内查，否则样本已滑出——演示参数调大窗口（60s）再验。
