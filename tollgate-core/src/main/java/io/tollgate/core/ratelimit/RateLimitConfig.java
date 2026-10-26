package io.tollgate.core.ratelimit;

/**
 * 租户限流配置。两个维度均设为 0 表示不限制。
 *
 * <p><b>桶容量的取舍</b>：容量直接取整分钟配额，不做秒级均摊。这意味着允许
 * 瞬时突发吃满整分钟额度——对内部租户的 API 网关，突发是常态（批量任务），
 * 限流的目标是挡住持续超量，而不是抹平每个瞬间。如果要更平滑，把桶容量调低即可，
 * 配置项天然支持。
 *
 * @param requestsPerMinute 每分钟请求数上限，0 = 不限
 * @param tokensPerMinute   每分钟 token 数上限，0 = 不限
 */
public record RateLimitConfig(
        long requestsPerMinute,
        long tokensPerMinute
) {

    public RateLimitConfig {
        if (requestsPerMinute < 0 || tokensPerMinute < 0) {
            throw new IllegalArgumentException("限额不能为负");
        }
    }

    public static RateLimitConfig unlimited() {
        return new RateLimitConfig(0, 0);
    }

    public boolean requestsLimited() {
        return requestsPerMinute > 0;
    }

    public boolean tokensLimited() {
        return tokensPerMinute > 0;
    }

    /** 每毫秒应补充的请求数 */
    double requestRefillPerMillis() {
        return requestsPerMinute / 60_000.0;
    }

    /** 每毫秒应补充的 token 数 */
    double tokenRefillPerMillis() {
        return tokensPerMinute / 60_000.0;
    }
}
