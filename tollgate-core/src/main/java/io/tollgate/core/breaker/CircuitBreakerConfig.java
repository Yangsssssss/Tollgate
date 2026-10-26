package io.tollgate.core.breaker;

import java.time.Duration;

/**
 * 熔断器配置。用 record 保证不可变，可在多线程间安全共享。
 *
 * @param failureRateThreshold 失败率阈值，达到即打开熔断（0.0–1.0）
 * @param minimumCalls         窗口内最小样本数，样本不足时不触发熔断，避免冷启动误判
 * @param openDuration         打开后冷却时长，到期进入半开探测
 * @param halfOpenPermits      半开状态同时放行的探测请求数
 * @param windowDuration       滑动窗口总跨度
 * @param bucketCount          窗口分桶数，桶越细对突发越敏感，代价是内存与遍历开销
 */
public record CircuitBreakerConfig(
        double failureRateThreshold,
        int minimumCalls,
        Duration openDuration,
        int halfOpenPermits,
        Duration windowDuration,
        int bucketCount
) {

    public CircuitBreakerConfig {
        if (failureRateThreshold <= 0 || failureRateThreshold > 1) {
            throw new IllegalArgumentException("failureRateThreshold 必须在 (0,1] 区间");
        }
        if (minimumCalls < 1) {
            throw new IllegalArgumentException("minimumCalls 必须 >= 1");
        }
        if (halfOpenPermits < 1) {
            throw new IllegalArgumentException("halfOpenPermits 必须 >= 1");
        }
        if (bucketCount < 1) {
            throw new IllegalArgumentException("bucketCount 必须 >= 1");
        }
        if (windowDuration.isNegative() || windowDuration.isZero()) {
            throw new IllegalArgumentException("windowDuration 必须为正");
        }
    }

    /** 默认配置：10s 窗口分 10 桶，失败率 50% 且样本满 10 次时熔断，冷却 30s，半开放行 3 个探测 */
    public static CircuitBreakerConfig defaults() {
        return new CircuitBreakerConfig(
                0.5, 10, Duration.ofSeconds(30), 3,
                Duration.ofSeconds(10), 10
        );
    }
}
