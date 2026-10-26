package io.tollgate.core.breaker;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

/**
 * 车道熔断器：三态状态机，全非阻塞。
 *
 * <pre>
 *   CLOSED ──(失败率超阈且样本充足)──► OPEN
 *     ▲                                │
 *     │                          (冷却期结束)
 *     │                                ▼
 *     └────(任一探测成功)──── HALF_OPEN
 *                                      │
 *                              (任一探测失败)
 *                                      ▼
 *                                    OPEN
 * </pre>
 *
 * <p><b>为什么不用 Resilience4j</b>：它的熔断器基于信号量/线程池，等待配额时会阻塞线程。
 * 放进 WebFlux 的事件循环线程等于直接掐住吞吐——一个线程被阻塞，它负责的所有其他请求
 * 全部排队。这里所有状态迁移都是 CAS，没有任何阻塞点。
 *
 * <p><b>半开状态的并发控制</b>是这类实现最容易写错的地方：冷却期一过，如果无条件放行，
 * 积压的几百个请求会在同一瞬间涌向刚恢复的车道，把它再次打挂。所以半开时必须严格
 * 只放行 {@code halfOpenPermits} 个探测请求，用 {@link AtomicInteger} 的原子递减实现。
 *
 * <p><b>调用方契约</b>：{@link #tryAcquire()} 返回 true 后，必须在 finally 中调用
 * {@link #onSuccess()} 或 {@link #onFailure()}。半开状态下漏调会导致探测配额永久泄漏，
 * 熔断器再也无法恢复到 CLOSED。
 */
public final class LaneCircuitBreaker {

    public enum State {
        CLOSED, OPEN, HALF_OPEN
    }

    private final String laneCode;
    private final CircuitBreakerConfig config;
    private final SlidingWindow window;
    private final LongSupplier clock;

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    /** 熔断打开的截止时间戳（epoch millis），仅在 state == OPEN 时有意义 */
    private volatile long openUntilMillis;
    /** 半开状态下剩余的探测配额 */
    private final AtomicInteger halfOpenAvailable = new AtomicInteger();

    private final LongAdder rejectedCount = new LongAdder();
    private final LongAdder tripCount = new LongAdder();

    public LaneCircuitBreaker(String laneCode, CircuitBreakerConfig config) {
        this(laneCode, config, System::currentTimeMillis);
    }

    /** 测试用：注入可控时钟 */
    public LaneCircuitBreaker(String laneCode, CircuitBreakerConfig config, LongSupplier clock) {
        this.laneCode = laneCode;
        this.config = config;
        this.clock = clock;
        this.window = new SlidingWindow(config, clock);
    }

    /**
     * 尝试获得一次调用许可。
     *
     * @return true 表示可以调用上游；false 表示当前应直接跳过该车道
     */
    public boolean tryAcquire() {
        for (; ; ) {
            State current = state.get();
            switch (current) {
                case CLOSED -> {
                    return true;
                }
                case OPEN -> {
                    if (clock.getAsLong() < openUntilMillis) {
                        rejectedCount.increment();
                        return false;
                    }
                    // 冷却期结束，抢占式转入半开。只有一个线程能 CAS 成功，
                    // 由它负责重置探测配额，避免重复初始化。
                    if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                        window.reset();
                        halfOpenAvailable.set(config.halfOpenPermits());
                    }
                    // CAS 失败说明别的线程已转入半开，回到循环按 HALF_OPEN 处理
                }
                case HALF_OPEN -> {
                    int remaining = halfOpenAvailable.decrementAndGet();
                    if (remaining >= 0) {
                        return true;
                    }
                    // 配额耗尽，还回去再拒绝，否则配额会被"借"走一直不归还
                    halfOpenAvailable.incrementAndGet();
                    rejectedCount.increment();
                    return false;
                }
            }
        }
    }

    public void onSuccess() {
        State current = state.get();
        if (current == State.HALF_OPEN) {
            // 探测成功即判定上游已恢复。宁可乐观关闭——即使立即再被打挂，
            // 失败率会迅速再次触发熔断，代价只是多一次切换。
            if (state.compareAndSet(State.HALF_OPEN, State.CLOSED)) {
                window.reset();
            }
            return;
        }
        if (current == State.CLOSED) {
            window.recordSuccess();
            // 成功也要评估：样本数达到 minimumCalls 的那一刻可能恰好满足熔断条件
            // （例如 2 失败 2 成功、阈值 50%），若只在失败时评估，这次熔断要等到
            // 下一次失败才触发，中间会多放行一批注定失败的请求。
            evaluateTrip();
        }
    }

    public void onFailure() {
        State current = state.get();
        if (current == State.HALF_OPEN) {
            // 探测失败：立即重新打开，并重新计时冷却期
            openUntilMillis = clock.getAsLong() + config.openDuration().toMillis();
            if (state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
                tripCount.increment();
            }
            return;
        }
        if (current == State.CLOSED) {
            window.recordFailure();
            evaluateTrip();
        }
    }

    /**
     * 评估是否应当熔断。只在 CLOSED 状态下有效。
     *
     * <p>短路顺序有意为之：先看样本量再看失败率。样本不足时直接返回，
     * 避免冷启动阶段的个位数统计误判——新车道刚上线就 1 次失败，不应该被熔断。
     */
    private void evaluateTrip() {
        long total = window.totalCount();
        if (total < config.minimumCalls()) {
            return;
        }
        if (window.failureRate() < config.failureRateThreshold()) {
            return;
        }
        // 先写截止时间再 CAS：读到 state == OPEN 的线程必然能看到这个写，
        // 因为 CAS 提供了 happens-before 关系
        openUntilMillis = clock.getAsLong() + config.openDuration().toMillis();
        if (state.compareAndSet(State.CLOSED, State.OPEN)) {
            tripCount.increment();
        }
    }

    public State state() {
        return state.get();
    }

    public String laneCode() {
        return laneCode;
    }

    /** 距熔断结束还剩多少毫秒，非 OPEN 状态返回 0 */
    public long remainingOpenMillis() {
        if (state.get() != State.OPEN) {
            return 0;
        }
        return Math.max(0, openUntilMillis - clock.getAsLong());
    }

    public long rejectedCount() {
        return rejectedCount.sum();
    }

    /** 累计熔断次数，用于观测车道稳定性 */
    public long tripCount() {
        return tripCount.sum();
    }

    public double currentFailureRate() {
        return window.failureRate();
    }

    public long currentSampleCount() {
        return window.totalCount();
    }

    @Override
    public String toString() {
        return "Breaker[lane=%s, state=%s, rate=%.2f, samples=%d, trips=%d]".formatted(
                laneCode, state.get(), currentFailureRate(), currentSampleCount(), tripCount.sum());
    }
}
