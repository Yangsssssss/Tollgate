package io.tollgate.app.dataplane;

import io.tollgate.app.dataplane.CatalogSnapshot.ApiKeyCredential;
import io.tollgate.app.dataplane.CatalogSnapshot.CatalogLane;
import io.tollgate.app.dataplane.CatalogSnapshot.ModelRoute;
import io.tollgate.core.breaker.CircuitBreakerConfig;
import io.tollgate.core.dispatch.CandidateResolver;
import io.tollgate.core.dispatch.LaneSelection;
import io.tollgate.core.dispatch.LaneSelector;
import io.tollgate.core.keypool.KeyPool;
import io.tollgate.core.keypool.KeyPoolConfig;
import io.tollgate.core.lane.Lane;
import io.tollgate.core.ratelimit.TenantRateLimiter;
import io.tollgate.common.error.ErrorCode;
import io.tollgate.common.error.TollgateException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 调度运行时：把内存配置目录接到调度内核上的装配层，也是数据面的唯一门面。
 *
 * <p><b>关键设计——内核常驻、目录热换</b>：{@link CandidateResolver} 和
 * {@link LaneSelector} 启动时构建一次，它们依赖的 {@code LaneDirectory} /
 * {@code KeyPoolFactory} 是函数式接口，实现里每次调用都从
 * {@code CatalogLoader#current()} 取最新快照。于是配置刷新只是换一个引用：
 * 熔断器状态、密钥池游标全部无损保留，切换对请求路径零感知。
 * CandidateResolver 里的 (id, weight) 签名检测会自动感知权重变化重建轮询器，
 * 之前为"配置变更"预留的机制在这里真正用上了。
 */
@Component
public class DispatchRuntime {

    private final CatalogLoader catalog;
    private final CandidateResolver resolver;
    private final LaneSelector selector;

    /** 每租户独立限流器（桶状态按租户隔离）；配置刷新时整体重建，桶计数清零 */
    private volatile Map<Long, TenantRateLimiter> rateLimiters = Map.of();

    public DispatchRuntime(CatalogLoader catalog) {
        this.catalog = catalog;
        this.resolver = new CandidateResolver(this::lanesForModel, lane -> false);
        this.selector = new LaneSelector(resolver, this::keyPoolFor,
                null, CircuitBreakerConfig.defaults(),
                KeyPoolConfig.defaults(), System::currentTimeMillis);
        this.rebuildRateLimiters();
    }

    /** 由配置目录实时构建某模型的候选车道，权重应用 model_lane 覆盖 */
    private List<Lane> lanesForModel(String model) {
        List<ModelRoute> routes = catalog.current().routes().get(model);
        if (routes == null || routes.isEmpty()) {
            return List.of();
        }
        return routes.stream()
                .map(route -> {
                    CatalogLane lane = catalog.current().lanes().get(route.laneId());
                    if (lane == null) {
                        return null; // 车道已禁用/删除，路由悬空，跳过
                    }
                    int weight = route.weightOverride() != null
                            ? route.weightOverride() : lane.weight();
                    // Lane.providerCode 携带 protocol，数据面据此选择 UpstreamAdapter
                    return new Lane(lane.id(), lane.code(), lane.protocol(),
                            lane.baseUrl(), lane.priority(), weight, lane.timeoutMillis());
                })
                .filter(lane -> lane != null)
                .toList();
    }

    /** 密钥池按需惰性构建，密钥来自当前快照 */
    private KeyPool keyPoolFor(Lane lane) {
        // V1.3 密钥只存内存池，lane_key 表的启动加载在控制面接入时补
        // （演示种子的密钥是占位符，真实密钥由控制面 CRUD 写入）
        List<KeyPool.LaneKey> keys = catalog.current().laneSecrets().getOrDefault(
                lane.id(), List.of()).stream()
                .map(secret -> new KeyPool.LaneKey(secret.hashCode(), secret))
                .toList();
        return new KeyPool(keys, KeyPoolConfig.defaults());
    }

    public LaneSelection select(String tenantCode, String model, long estimatedTokens) {
        return selector.select(tenantCode, model, estimatedTokens);
    }

    /** 模型是否有可用路由（配置层判断，不含熔断状态） */
    public boolean hasRoutes(String model) {
        List<ModelRoute> routes = catalog.current().routes().get(model);
        return routes != null && !routes.isEmpty();
    }

    /** 认证：SHA-256 hex 直接查快照 map，O(1) 无 IO */
    public ApiKeyCredential authenticate(String keyHashHex) {
        ApiKeyCredential cred = catalog.current().credentials().get(keyHashHex);
        if (cred == null) {
            throw new TollgateException(ErrorCode.INVALID_API_KEY);
        }
        return cred;
    }

    /** 模型路由校验 + 取上游模型名；未知模型抛 404 */
    public String upstreamModelOf(String modelAlias, long laneId) {
        List<ModelRoute> routes = catalog.current().routes().get(modelAlias);
        if (routes == null) {
            throw new TollgateException(ErrorCode.MODEL_NOT_FOUND);
        }
        return routes.stream()
                .filter(r -> r.laneId() == laneId)
                .findFirst()
                .map(ModelRoute::upstreamModel)
                .orElseThrow(() -> new TollgateException(ErrorCode.MODEL_NOT_FOUND));
    }

    /** 租户限流检查，超限抛 429 */
    public void checkRate(long tenantId, long estimatedTokens) {
        TenantRateLimiter limiter = rateLimiters.get(tenantId);
        if (limiter == null) {
            return; // 租户无额度配置 = 不限
        }
        switch (limiter.tryAcquire("default", estimatedTokens)) {
            case OK -> { }
            case REQUEST_REJECTED -> throw new TollgateException(ErrorCode.TENANT_RATE_LIMITED);
            case TOKEN_REJECTED -> throw new TollgateException(ErrorCode.TENANT_TOKEN_LIMITED);
        }
    }

    private void rebuildRateLimiters() {
        // 演示种子里所有租户共用 rpm/tpm 列；按租户建独立限流器
        Map<Long, TenantRateLimiter> rebuilt = new ConcurrentHashMap<>();
        catalog.current().credentials().values().forEach(cred -> rebuilt.computeIfAbsent(
                cred.tenantId(),
                id -> new TenantRateLimiter(new io.tollgate.core.ratelimit.RateLimitConfig(
                        cred.rpmLimit(), cred.tpmLimit()))));
        this.rateLimiters = Map.copyOf(rebuilt);
    }

    /** 供控制面触发刷新后重建租户限流器（阈值可能变了） */
    public void onCatalogRefreshed() {
        rebuildRateLimiters();
    }

    /** 暴露给观测端点：某车道的熔断器状态 */
    public io.tollgate.core.breaker.LaneCircuitBreaker breakerOf(long laneId) {
        return selector.breakerOf(laneId);
    }
}
