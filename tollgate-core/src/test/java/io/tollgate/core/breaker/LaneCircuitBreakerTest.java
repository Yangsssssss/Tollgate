package io.tollgate.core.breaker;

import io.tollgate.core.breaker.LaneCircuitBreaker.State;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class LaneCircuitBreakerTest {

    private AtomicLong now;
    private LaneCircuitBreaker breaker;

    /** 4 次样本、50% 失败率触发，冷却 30s，半开放行 2 个探测 */
    private static final CircuitBreakerConfig CONFIG = new CircuitBreakerConfig(
            0.5, 4, Duration.ofSeconds(30), 2, Duration.ofSeconds(10), 10);

    @BeforeEach
    void setUp() {
        now = new AtomicLong(1_000_000L);
        breaker = new LaneCircuitBreaker("lane-test", CONFIG, now::get);
    }

    @Test
    @DisplayName("初始为 CLOSED，直接放行")
    void startsClosed() {
        assertThat(breaker.state()).isEqualTo(State.CLOSED);
        assertThat(breaker.tryAcquire()).isTrue();
    }

    @Test
    @DisplayName("样本不足时不熔断，避免冷启动误判")
    void doesNotTripBelowMinimumCalls() {
        // 3 次全失败，但 minimumCalls = 4
        breaker.onFailure();
        breaker.onFailure();
        breaker.onFailure();

        assertThat(breaker.state()).isEqualTo(State.CLOSED);
        assertThat(breaker.currentSampleCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("失败率达到阈值后熔断打开")
    void tripsWhenFailureRateExceedsThreshold() {
        breaker.onFailure();
        breaker.onFailure();
        breaker.onSuccess();
        breaker.onSuccess();

        assertThat(breaker.currentFailureRate()).isEqualTo(0.5);
        assertThat(breaker.state()).isEqualTo(State.OPEN);
        assertThat(breaker.tripCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("OPEN 期间拒绝所有请求，并累计拒绝次数")
    void rejectsWhileOpen() {
        tripBreaker();

        assertThat(breaker.tryAcquire()).isFalse();
        assertThat(breaker.tryAcquire()).isFalse();
        assertThat(breaker.rejectedCount()).isEqualTo(2);
        assertThat(breaker.state()).isEqualTo(State.OPEN);
    }

    @Test
    @DisplayName("冷却期结束后转入 HALF_OPEN，且严格限制探测配额")
    void limitsProbesInHalfOpen() {
        tripBreaker();
        advance(Duration.ofSeconds(30));

        // 前 2 次占用探测配额
        assertThat(breaker.tryAcquire()).isTrue();
        assertThat(breaker.tryAcquire()).isTrue();
        // 第 3 次必须被拒——这是防止刚恢复的车道被瞬间打满的关键
        assertThat(breaker.tryAcquire()).isFalse();
        assertThat(breaker.state()).isEqualTo(State.HALF_OPEN);
    }

    @Test
    @DisplayName("半开状态下探测成功即恢复 CLOSED")
    void recoversOnProbeSuccess() {
        tripBreaker();
        advance(Duration.ofSeconds(30));
        assertThat(breaker.tryAcquire()).isTrue();

        breaker.onSuccess();

        assertThat(breaker.state()).isEqualTo(State.CLOSED);
        // 恢复后窗口被重置，旧统计不参与下一轮判定
        assertThat(breaker.currentSampleCount()).isZero();
        assertThat(breaker.tryAcquire()).isTrue();
    }

    @Test
    @DisplayName("半开状态下探测失败立即重新打开，并重置冷却计时")
    void reopensOnProbeFailure() {
        tripBreaker();
        advance(Duration.ofSeconds(30));
        assertThat(breaker.tryAcquire()).isTrue();

        breaker.onFailure();

        assertThat(breaker.state()).isEqualTo(State.OPEN);
        assertThat(breaker.tripCount()).isEqualTo(2);
        // 重新计时：此刻仍在冷却期内，不放行
        assertThat(breaker.tryAcquire()).isFalse();
    }

    @Test
    @DisplayName("冷却期未满时不会因时间推进而误判为半开")
    void staysOpenBeforeCooldownEnds() {
        tripBreaker();
        advance(Duration.ofSeconds(29));

        assertThat(breaker.state()).isEqualTo(State.OPEN);
        assertThat(breaker.tryAcquire()).isFalse();
        assertThat(breaker.remainingOpenMillis()).isEqualTo(1000);
    }

    private void tripBreaker() {
        breaker.onFailure();
        breaker.onFailure();
        breaker.onSuccess();
        breaker.onSuccess();
        assertThat(breaker.state()).isEqualTo(State.OPEN);
    }

    private void advance(Duration duration) {
        now.addAndGet(duration.toMillis());
    }
}
