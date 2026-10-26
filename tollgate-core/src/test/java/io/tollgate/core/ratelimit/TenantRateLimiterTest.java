package io.tollgate.core.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantRateLimiterTest {

    @Test
    @DisplayName("RPM：请求数耗尽后拒绝，且 token 维度不受影响")
    void rejectsWhenRequestQuotaExhausted() {
        long[] now = {1_000_000};
        var limiter = new TenantRateLimiter(new RateLimitConfig(3, 0), () -> now[0]);

        assertThat(limiter.tryAcquire("t1", 100)).isEqualTo(TenantRateLimiter.Result.OK);
        assertThat(limiter.tryAcquire("t1", 100)).isEqualTo(TenantRateLimiter.Result.OK);
        assertThat(limiter.tryAcquire("t1", 100)).isEqualTo(TenantRateLimiter.Result.OK);
        assertThat(limiter.tryAcquire("t1", 100)).isEqualTo(TenantRateLimiter.Result.REQUEST_REJECTED);
    }

    @Test
    @DisplayName("TPM：token 额度不足时拒绝并退还请求额度")
    void refundsRequestQuotaWhenTokensInsufficient() {
        long[] now = {1_000_000};
        // 1000 token/分钟，请求维度 2 次
        var limiter = new TenantRateLimiter(new RateLimitConfig(2, 1_000), () -> now[0]);

        assertThat(limiter.tryAcquire("t1", 600)).isEqualTo(TenantRateLimiter.Result.OK);
        assertThat(limiter.tryAcquire("t1", 600)).isEqualTo(TenantRateLimiter.Result.TOKEN_REJECTED);

        // 请求额度被退回：仍剩 2 次请求额度，换一个小请求可以通过
        assertThat(limiter.tryAcquire("t1", 400)).isEqualTo(TenantRateLimiter.Result.OK);
    }

    @Test
    @DisplayName("时间推移按比例补充令牌，而非整分钟一次性到账")
    void refillsContinuously() {
        long[] now = {1_000_000};
        // 60 req/min = 1 req/秒
        var limiter = new TenantRateLimiter(new RateLimitConfig(60, 0), () -> now[0]);

        for (int i = 0; i < 60; i++) {
            limiter.tryAcquire("t1", 0);
        }
        assertThat(limiter.tryAcquire("t1", 0)).isEqualTo(TenantRateLimiter.Result.REQUEST_REJECTED);

        // 推进 5 秒，应恰好补充 5 个令牌
        now[0] += 5_000;
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire("t1", 0)).isEqualTo(TenantRateLimiter.Result.OK);
        }
        assertThat(limiter.tryAcquire("t1", 0)).isEqualTo(TenantRateLimiter.Result.REQUEST_REJECTED);
    }

    @Test
    @DisplayName("不同租户配额相互独立")
    void tenantsAreIsolated() {
        var limiter = new TenantRateLimiter(new RateLimitConfig(1, 0));

        assertThat(limiter.tryAcquire("t1", 0)).isEqualTo(TenantRateLimiter.Result.OK);
        assertThat(limiter.tryAcquire("t1", 0)).isEqualTo(TenantRateLimiter.Result.REQUEST_REJECTED);
        assertThat(limiter.tryAcquire("t2", 0)).isEqualTo(TenantRateLimiter.Result.OK);
    }

    @Test
    @DisplayName("未启用的维度不参与判定")
    void unlimitedDimensionsAreSkipped() {
        var limiter = new TenantRateLimiter(RateLimitConfig.unlimited());

        for (int i = 0; i < 1000; i++) {
            assertThat(limiter.tryAcquire("t1", 999_999)).isEqualTo(TenantRateLimiter.Result.OK);
        }
        assertThat(limiter.remainingRequests("t1")).isEqualTo(-1);
        assertThat(limiter.remainingTokens("t1")).isEqualTo(-1);
    }

    @Test
    @DisplayName("非法配置直接拒绝")
    void rejectsInvalidConfig() {
        assertThatThrownBy(() -> new RateLimitConfig(-1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitConfig(0, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
