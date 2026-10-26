package io.tollgate.core.dispatch;

import io.tollgate.core.breaker.CircuitBreakerConfig;
import io.tollgate.core.keypool.KeyPool;
import io.tollgate.core.keypool.KeyPoolConfig;
import io.tollgate.core.lane.Lane;
import io.tollgate.core.ratelimit.RateLimitConfig;
import io.tollgate.core.ratelimit.TenantRateLimiter;
import io.tollgate.common.error.ErrorCode;
import io.tollgate.common.error.TollgateException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LaneSelectorTest {

    /** 可控时钟，所有组件共享同一时间源 */
    private long now = 1_000_000;

    /** 熔断阈值刻意调低：2 个样本、失败率 50% 即熔断，冷却 30s */
    private final CircuitBreakerConfig breakerConfig =
            new CircuitBreakerConfig(0.5, 2, Duration.ofSeconds(30), 1, Duration.ofSeconds(10), 2);
    /** 密钥封禁阈值调到极高，让密钥池在熔断类测试中保持静默，两个机制分开验证 */
    private final KeyPoolConfig keyConfig = new KeyPoolConfig(Integer.MAX_VALUE, Duration.ofSeconds(60));

    private static Lane lane(long id, String code, int priority, int weight) {
        return new Lane(id, code, "openai", "https://upstream.example", priority, weight, 30_000);
    }

    private LaneSelector selector(List<Lane> lanes, Map<Long, List<KeyPool.LaneKey>> keysByLane) {
        return selector(lanes, keysByLane, null, keyConfig);
    }

    private LaneSelector selector(List<Lane> lanes, Map<Long, List<KeyPool.LaneKey>> keysByLane,
                                  TenantRateLimiter rateLimiter, KeyPoolConfig poolConfig) {
        CandidateResolver resolver = new CandidateResolver(
                model -> "gpt-4o-mini".equals(model) ? lanes : List.of(),
                lane -> false); // 预筛关闭，真实门控交给熔断器
        Map<Long, KeyPool> pools = new ConcurrentHashMap<>();
        return new LaneSelector(resolver,
                l -> pools.computeIfAbsent(l.id(), id ->
                        new KeyPool(keysByLane.getOrDefault(id, List.of()), poolConfig, () -> now)),
                rateLimiter, breakerConfig, poolConfig, () -> now);
    }

    @Test
    @DisplayName("优先级分层：高优先级车道可用时永远轮不到低优先级")
    void prefersHigherPriorityTier() {
        var lanes = List.of(
                lane(2, "lane-backup", 1, 10),
                lane(1, "lane-primary", 0, 1));
        var keys = Map.of(
                1L, List.of(new KeyPool.LaneKey(11, "sk-1")),
                2L, List.of(new KeyPool.LaneKey(21, "sk-2")));
        var selector = selector(lanes, keys);

        for (int i = 0; i < 5; i++) {
            assertThat(selector.select("tenant", "gpt-4o-mini", 100).lane().id()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("同层 1:1 权重在连续选择中交替分布")
    void distributesEvenlyWithinTier() {
        var lanes = List.of(
                lane(1, "lane-a", 0, 1),
                lane(2, "lane-b", 0, 1));
        var keys = Map.of(
                1L, List.of(new KeyPool.LaneKey(11, "sk-1")),
                2L, List.of(new KeyPool.LaneKey(21, "sk-2")));
        var selector = selector(lanes, keys);

        long first = selector.select("t", "gpt-4o-mini", 0).lane().id();
        long second = selector.select("t", "gpt-4o-mini", 0).lane().id();
        long third = selector.select("t", "gpt-4o-mini", 0).lane().id();

        assertThat(first).isEqualTo(third);
        assertThat(second).isNotEqualTo(first);
    }

    @Test
    @DisplayName("故障转移：主车道熔断后自动切到备车道")
    void failsOverWhenBreakerOpens() {
        var lanes = List.of(
                lane(1, "lane-primary", 0, 1),
                lane(2, "lane-backup", 1, 1));
        var keys = Map.of(
                1L, List.of(new KeyPool.LaneKey(11, "sk-1")),
                2L, List.of(new KeyPool.LaneKey(21, "sk-2")));
        var selector = selector(lanes, keys);

        // 主车道连续失败两次 → 失败率 100% >= 50%，样本 2 >= 2，熔断
        var s1 = selector.select("t", "gpt-4o-mini", 0);
        assertThat(s1.lane().id()).isEqualTo(1);
        s1.fail();
        var s2 = selector.select("t", "gpt-4o-mini", 0);
        assertThat(s2.lane().id()).isEqualTo(1);
        s2.fail();

        assertThat(selector.select("t", "gpt-4o-mini", 0).lane().id()).isEqualTo(2);
    }

    @Test
    @DisplayName("密钥封禁：主车道密钥全挂后切到备车道，且不消耗主车道熔断配额")
    void failsOverWhenKeysBanned() {
        var lanes = List.of(
                lane(1, "lane-primary", 0, 1),
                lane(2, "lane-backup", 1, 1));
        var keys = Map.of(
                1L, List.of(new KeyPool.LaneKey(11, "sk-1")),
                2L, List.of(new KeyPool.LaneKey(21, "sk-2")));
        // 密钥阈值 1：失败一次即封禁（独立的激进配置）
        var selector = selector(lanes, keys, null, new KeyPoolConfig(1, Duration.ofSeconds(60)));

        selector.select("t", "gpt-4o-mini", 0).fail(); // 密钥 11 被封禁

        // 后续请求全部落到备车道
        for (int i = 0; i < 3; i++) {
            assertThat(selector.select("t", "gpt-4o-mini", 0).lane().id()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("全部车道不可用时抛 NO_AVAILABLE_LANE(503)")
    void throwsWhenNothingAvailable() {
        var lanes = List.of(lane(1, "lane-a", 0, 1));
        var selector = selector(lanes, Map.of()); // 没有任何密钥

        assertThatThrownBy(() -> selector.select("t", "gpt-4o-mini", 0))
                .isInstanceOfSatisfying(TollgateException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NO_AVAILABLE_LANE));
    }

    @Test
    @DisplayName("未知模型同样抛 NO_AVAILABLE_LANE")
    void throwsForUnknownModel() {
        var selector = selector(List.of(lane(1, "lane-a", 0, 1)), Map.of());

        assertThatThrownBy(() -> selector.select("t", "unknown-model", 0))
                .isInstanceOfSatisfying(TollgateException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NO_AVAILABLE_LANE));
    }

    @Test
    @DisplayName("限流：请求数超限抛 429，且不消耗候选车道状态")
    void rateLimitedBeforeSelection() {
        var lanes = List.of(lane(1, "lane-a", 0, 1));
        var keys = Map.of(1L, List.of(new KeyPool.LaneKey(11, "sk-1")));
        var limiter = new TenantRateLimiter(new RateLimitConfig(1, 0), () -> now);
        var selector = selector(lanes, keys, limiter, keyConfig);

        assertThat(selector.select("t", "gpt-4o-mini", 0)).isNotNull();
        assertThatThrownBy(() -> selector.select("t", "gpt-4o-mini", 0))
                .isInstanceOfSatisfying(TollgateException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TENANT_RATE_LIMITED));
    }

    @Test
    @DisplayName("succeed() 正常放行后续请求，不触发熔断")
    void successKeepsLaneHealthy() {
        var lanes = List.of(lane(1, "lane-a", 0, 1));
        var keys = Map.of(1L, List.of(new KeyPool.LaneKey(11, "sk-1")));
        var selector = selector(lanes, keys);

        for (int i = 0; i < 10; i++) {
            var selection = selector.select("t", "gpt-4o-mini", 0);
            assertThat(selection.lane().id()).isEqualTo(1);
            selection.succeed();
        }
    }

    @Test
    @DisplayName("冷却期满后熔断车道恢复服务")
    void breakerRecoversAfterCooldown() {
        var lanes = List.of(lane(1, "lane-a", 0, 1));
        var keys = Map.of(1L, List.of(new KeyPool.LaneKey(11, "sk-1")));
        var selector = selector(lanes, keys);

        selector.select("t", "gpt-4o-mini", 0).fail();
        selector.select("t", "gpt-4o-mini", 0).fail();
        assertThatThrownBy(() -> selector.select("t", "gpt-4o-mini", 0))
                .isInstanceOf(TollgateException.class); // 熔断中

        now += Duration.ofSeconds(31).toMillis(); // 冷却期过，进入半开
        var probe = selector.select("t", "gpt-4o-mini", 0); // 半开配额 1，探测放行
        assertThat(probe.lane().id()).isEqualTo(1);
        probe.succeed(); // 探测成功 → 回到 CLOSED
        assertThat(selector.select("t", "gpt-4o-mini", 0).lane().id()).isEqualTo(1);
    }
}
