package io.tollgate.core.dispatch;

import io.tollgate.core.breaker.CircuitBreakerConfig;
import io.tollgate.core.breaker.LaneCircuitBreaker;
import io.tollgate.core.keypool.KeyPool;
import io.tollgate.core.keypool.KeyPoolConfig;
import io.tollgate.core.lane.Lane;
import io.tollgate.core.ratelimit.TenantRateLimiter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 调度统一入口：限流 → 候选解析 → 密钥轮换 → 熔断门控，一次 {@link #select()}
 * 走完全部四步。app 层的数据面只需要跟这一个类打交道。
 *
 * <p><b>选路顺序的讲究</b>：先取密钥再过熔断。反过来（先熔断再取密钥）会有一个
 * 隐蔽的泄漏：车道处于半开、tryAcquire 消耗了探测配额，但密钥池恰好全被封禁
 * 返回 null——这个探测名额既没被使用也没被归还，半开配额被永久占用，
 * 熔断器再也回不到 CLOSED。先拿密钥可以保证：只要走过了密钥这关，
 * 熔断拒绝是无副作用的（轮询游标偏移一位，无害）。
 *
 * <p><b>熔断器的生命周期</b>：按 laneId 惰性创建、常驻内存。车道从配置中删除后
 * 其熔断器成为垃圾，由 map 的 key 一起回收——没有显式注销的必要，
 * 因为 Lane 列表每次请求都从目录重新拉取。
 */
public final class LaneSelector {

    /** 密钥池来源：app 层从配置缓存构建，core 不关心密钥存在哪里 */
    @FunctionalInterface
    public interface KeyPoolFactory {
        KeyPool keyPoolFor(Lane lane);
    }

    private final CandidateResolver resolver;
    private final KeyPoolFactory keyPoolFactory;
    private final TenantRateLimiter rateLimiter;
    private final CircuitBreakerConfig breakerConfig;
    private final KeyPoolConfig keyPoolConfig;
    private final LongSupplier clock;

    private final Map<Long, LaneCircuitBreaker> breakers = new ConcurrentHashMap<>();
    private final Map<Long, KeyPool> keyPools = new ConcurrentHashMap<>();

    /** 限流关闭的便捷构造 */
    public LaneSelector(CandidateResolver resolver, KeyPoolFactory keyPoolFactory) {
        this(resolver, keyPoolFactory, null,
                CircuitBreakerConfig.defaults(), KeyPoolConfig.defaults(), System::currentTimeMillis);
    }

    public LaneSelector(CandidateResolver resolver, KeyPoolFactory keyPoolFactory,
                        TenantRateLimiter rateLimiter, CircuitBreakerConfig breakerConfig,
                        KeyPoolConfig keyPoolConfig, LongSupplier clock) {
        this.resolver = resolver;
        this.keyPoolFactory = keyPoolFactory;
        this.rateLimiter = rateLimiter;
        this.breakerConfig = breakerConfig;
        this.keyPoolConfig = keyPoolConfig;
        this.clock = clock;
    }

    /**
     * 为一次请求选定车道与密钥。
     *
     * @param tenantId        租户标识，用于限流维度隔离
     * @param model           模型编码
     * @param estimatedTokens 预估 token 消耗，仅用于 TPM 限流
     * @return 选定结果；调用方结束后必须调用 {@link LaneSelection#succeed()} 或 {@link #fail()}
     * @throws io.tollgate.common.error.TollgateException 限流超限（429）或无可用车道（503）
     */
    public LaneSelection select(String tenantId, String model, long estimatedTokens) {
        if (rateLimiter != null) {
            TenantRateLimiter.Result limit = rateLimiter.tryAcquire(tenantId, estimatedTokens);
            throwOnLimited(limit);
        }
        List<Lane> candidates = resolver.candidates(model);
        for (Lane lane : candidates) {
            KeyPool pool = keyPools.computeIfAbsent(lane.id(), id -> keyPoolFactory.keyPoolFor(lane));
            KeyPool.LaneKey key = pool.acquire();
            if (key == null) {
                continue; // 该车道密钥全被封禁，换下一条
            }
            LaneCircuitBreaker breaker = breakers.computeIfAbsent(lane.id(),
                    id -> new LaneCircuitBreaker(lane.code(), breakerConfig, clock));
            if (!breaker.tryAcquire()) {
                continue; // 熔断拒绝，换下一条
            }
            return new LaneSelection(lane, key, new BreakerHandle(breaker), pool);
        }
        throw new io.tollgate.common.error.TollgateException(
                io.tollgate.common.error.ErrorCode.NO_AVAILABLE_LANE,
                "模型 %s 无可用车道（候选 %d 条）".formatted(model, candidates.size()));
    }

    private void throwOnLimited(TenantRateLimiter.Result limit) {
        switch (limit) {
            case OK -> { }
            case REQUEST_REJECTED -> throw new io.tollgate.common.error.TollgateException(
                    io.tollgate.common.error.ErrorCode.TENANT_RATE_LIMITED);
            case TOKEN_REJECTED -> throw new io.tollgate.common.error.TollgateException(
                    io.tollgate.common.error.ErrorCode.TENANT_TOKEN_LIMITED);
        }
    }

    /** 直接暴露某条车道的熔断器，供观测端点读取状态 */
    public LaneCircuitBreaker breakerOf(long laneId) {
        return breakers.get(laneId);
    }

    private final class BreakerHandle implements LaneSelection.LaneCircuitBreakerHandle {
        private final LaneCircuitBreaker breaker;

        BreakerHandle(LaneCircuitBreaker breaker) {
            this.breaker = breaker;
        }

        @Override
        public void onSuccess() {
            breaker.onSuccess();
        }

        @Override
        public void onFailure() {
            breaker.onFailure();
        }
    }
}
