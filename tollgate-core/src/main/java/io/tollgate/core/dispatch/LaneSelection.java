package io.tollgate.core.dispatch;

import io.tollgate.core.keypool.KeyPool;

/**
 * 一次车道选择的结果：选定哪条车道、用哪把密钥。
 *
 * <p>调用方拿到它去请求上游，结束后<b>必须</b>二选一调用 {@link #succeed()}
 * 或 {@link #fail()}——两个方法同时驱动熔断器与密钥池的状态推进，
 * 漏调会让半开探测配额泄漏、坏密钥无法封禁。
 */
public final class LaneSelection {

    private final io.tollgate.core.lane.Lane lane;
    private final KeyPool.LaneKey key;
    private final LaneCircuitBreakerHandle breaker;
    private final KeyPool keyPool;

    LaneSelection(io.tollgate.core.lane.Lane lane, KeyPool.LaneKey key,
                  LaneCircuitBreakerHandle breaker, KeyPool keyPool) {
        this.lane = lane;
        this.key = key;
        this.breaker = breaker;
        this.keyPool = keyPool;
    }

    public io.tollgate.core.lane.Lane lane() {
        return lane;
    }

    public KeyPool.LaneKey key() {
        return key;
    }

    /** 上游调用成功（含拿到 SSE 流并正常关闭）后调用 */
    public void succeed() {
        breaker.onSuccess();
        keyPool.reportSuccess(key);
    }

    /** 上游调用失败（连接拒绝、超时、5xx、429）后调用 */
    public void fail() {
        breaker.onFailure();
        keyPool.reportFailure(key);
    }

    /** 熔断器句柄：隔离 core 包内实现细节，避免调用方误触状态 */
    interface LaneCircuitBreakerHandle {
        void onSuccess();

        void onFailure();
    }
}
