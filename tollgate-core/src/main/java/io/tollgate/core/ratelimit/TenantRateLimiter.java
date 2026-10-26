package io.tollgate.core.ratelimit;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 租户级限流器：RPM（请求数）与 TPM（token 数）双维度令牌桶。
 *
 * <p><b>为什么自己写而不用 Guava RateLimiter</b>：Guava 只支持单维度 QPS，
 * 而大模型网关的配额天然是二维的——一次请求占 1 个请求额度 + 估算 token 额度，
 * 两个维度各自独立补充。而且 Guava 的令牌预支模型（acquire 阻塞等待）不适用：
 * 网关对超限请求要立即拒绝（429），不排队。
 *
 * <p><b>实现取舍</b>：桶操作用 synchronized 而非 CAS。临界区是一次惰性补充
 * 加一次减法，纳秒级；锁粒度是单个租户的桶，不同租户完全并行。用 CAS 做 double
 * 运算需要按位转换，代码可读性差且收益趋近于零。
 *
 * <p><b>原子性</b>：请求维度扣减成功而 token 维度不足时，立即退还请求额度，
 * 不出现"扣了请求却没活干"的泄漏。
 *
 * <p><b>内存</b>：每个租户两个桶。V1 的租户是内部团队级别（个位数~两位数），
 * 不做淘汰；若未来开放公网注册，需加 LRU 或过期清理，届时改动点只在
 * {@code tenants} 这个 map 上。
 */
public final class TenantRateLimiter {

    public enum Result {
        /** 放行 */
        OK,
        /** 请求数超限 */
        REQUEST_REJECTED,
        /** token 数超限 */
        TOKEN_REJECTED
    }

    private final RateLimitConfig config;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, TenantBuckets> tenants = new ConcurrentHashMap<>();

    public TenantRateLimiter(RateLimitConfig config) {
        this(config, System::currentTimeMillis);
    }

    /** 测试用：注入可控时钟 */
    public TenantRateLimiter(RateLimitConfig config, LongSupplier clock) {
        this.config = config;
        this.clock = clock;
    }

    /**
     * 尝试为一次请求扣减额度。
     *
     * @param estimatedTokens 该请求预估消耗的 token 数（prompt 估算 + max_tokens），
     *                        实际用量在出口按真实值记账，预估偏差由下游兜底
     */
    public Result tryAcquire(String tenantId, long estimatedTokens) {
        TenantBuckets buckets = tenants.computeIfAbsent(tenantId, k -> new TenantBuckets());
        if (config.requestsLimited() && !buckets.requests.tryTake(1, clock.getAsLong())) {
            return Result.REQUEST_REJECTED;
        }
        if (config.tokensLimited()
                && !buckets.tokens.tryTake(Math.max(0, estimatedTokens), clock.getAsLong())) {
            // 请求额度已扣、token 不足：退还要求数，避免凭空消耗
            if (config.requestsLimited()) {
                buckets.requests.refund(1, clock.getAsLong());
            }
            return Result.TOKEN_REJECTED;
        }
        return Result.OK;
    }

    /** 该租户请求桶的剩余额度（未启用时返回 -1），用于观测与响应头 */
    public double remainingRequests(String tenantId) {
        if (!config.requestsLimited()) {
            return -1;
        }
        return bucket(tenantId).requests.available(clock.getAsLong());
    }

    /** 该租户 token 桶的剩余额度（未启用时返回 -1） */
    public double remainingTokens(String tenantId) {
        if (!config.tokensLimited()) {
            return -1;
        }
        return bucket(tenantId).tokens.available(clock.getAsLong());
    }

    private TenantBuckets bucket(String tenantId) {
        return tenants.computeIfAbsent(tenantId, k -> new TenantBuckets());
    }

    private final class TenantBuckets {
        private final Bucket requests =
                new Bucket(config.requestsPerMinute(), config.requestRefillPerMillis());
        private final Bucket tokens =
                new Bucket(config.tokensPerMinute(), config.tokenRefillPerMillis());
    }

    /**
     * 惰性补充令牌桶。容量取整分钟配额（允许突发吃满一分钟额度），
     * 令牌按流逝时间线性补充。初始满桶，允许启动后的第一波突发。
     */
    private final class Bucket {
        private final double capacity;
        private final double refillPerMillis;
        private double tokens;
        private long lastRefillMillis = clock.getAsLong();

        Bucket(double capacity, double refillPerMillis) {
            this.capacity = capacity;
            this.refillPerMillis = refillPerMillis;
            this.tokens = capacity;
        }

        synchronized boolean tryTake(double amount, long now) {
            refill(now);
            if (tokens < amount) {
                return false;
            }
            tokens -= amount;
            return true;
        }

        synchronized void refund(double amount, long now) {
            refill(now);
            tokens = Math.min(capacity, tokens + amount);
        }

        synchronized double available(long now) {
            refill(now);
            return tokens;
        }

        private void refill(long now) {
            long elapsed = now - lastRefillMillis;
            if (elapsed <= 0) {
                return;
            }
            tokens = Math.min(capacity, tokens + elapsed * refillPerMillis);
            lastRefillMillis = now;
        }
    }

}
