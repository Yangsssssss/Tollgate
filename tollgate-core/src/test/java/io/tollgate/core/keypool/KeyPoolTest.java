package io.tollgate.core.keypool;

import io.tollgate.core.keypool.KeyPool.LaneKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeyPoolTest {

    private static final KeyPoolConfig CONFIG = new KeyPoolConfig(3, Duration.ofSeconds(60));

    @Test
    @DisplayName("轮换：连续取用按游标顺序遍历所有密钥")
    void rotatesAcrossKeys() {
        var pool = new KeyPool(List.of(
                new LaneKey(1, "sk-a"), new LaneKey(2, "sk-b"), new LaneKey(3, "sk-c")), CONFIG);

        assertThat(pool.acquire().id()).isEqualTo(1);
        assertThat(pool.acquire().id()).isEqualTo(2);
        assertThat(pool.acquire().id()).isEqualTo(3);
        assertThat(pool.acquire().id()).isEqualTo(1);
    }

    @Test
    @DisplayName("封禁：连续失败达到阈值后不再被选中")
    void bansAfterConsecutiveFailures() {
        var k1 = new LaneKey(1, "sk-a");
        var k2 = new LaneKey(2, "sk-b");
        var pool = new KeyPool(List.of(k1, k2), CONFIG);

        for (int i = 0; i < CONFIG.consecutiveFailureThreshold(); i++) {
            pool.reportFailure(k1);
        }

        // k1 被封禁，连续取用只出 k2
        assertThat(pool.acquire().id()).isEqualTo(2);
        assertThat(pool.acquire().id()).isEqualTo(2);
        assertThat(pool.hasHealthyKey()).isTrue();
    }

    @Test
    @DisplayName("解封：封禁期满后密钥恢复可用")
    void recoversAfterBanExpires() {
        long[] now = {1_000_000};
        var k1 = new LaneKey(1, "sk-a");
        var k2 = new LaneKey(2, "sk-b");
        var pool = new KeyPool(List.of(k1, k2), CONFIG, () -> now[0]);

        for (int i = 0; i < CONFIG.consecutiveFailureThreshold(); i++) {
            pool.reportFailure(k1);
        }
        assertThat(pool.acquire().id()).isEqualTo(2);

        // 冷却 60s 未过
        now[0] += Duration.ofSeconds(59).toMillis();
        assertThat(pool.acquire().id()).isEqualTo(2);

        // 冷却期已过，恢复可用
        now[0] += Duration.ofSeconds(2).toMillis();
        assertThat(pool.acquire().id()).isEqualTo(1);
    }

    @Test
    @DisplayName("全部封禁时返回 null，调用方应跳过该车道")
    void returnsNullWhenAllBanned() {
        var pool = new KeyPool(List.of(new LaneKey(1, "sk-a")), CONFIG);

        assertThat(pool.acquire()).isNotNull();
        for (int i = 0; i < CONFIG.consecutiveFailureThreshold(); i++) {
            pool.reportFailure(pool.acquire());
        }

        assertThat(pool.acquire()).isNull();
        assertThat(pool.hasHealthyKey()).isFalse();
    }

    @Test
    @DisplayName("成功清零连续失败计数，间歇性失败不触发封禁")
    void successResetsConsecutiveFailures() {
        var k1 = new LaneKey(1, "sk-a");
        var k2 = new LaneKey(2, "sk-b");
        var pool = new KeyPool(List.of(k1, k2), CONFIG);

        pool.reportFailure(k1);
        pool.reportFailure(k1);
        pool.reportSuccess(k1);   // 2 连败后 1 次成功
        pool.reportFailure(k1);
        pool.reportFailure(k1);   // 计数重新从 1 数起，只到 2，未达阈值 3

        assertThat(pool.acquire().id()).isEqualTo(1);
    }

    @Test
    @DisplayName("LaneKey 的 toString 不泄漏 secret")
    void laneKeyToStringMasksSecret() {
        var key = new LaneKey(42, "sk-super-secret-value");
        assertThat(key.toString()).doesNotContain("sk-super-secret-value");
        assertThat(key.toString()).contains("42");
    }

    @Test
    @DisplayName("空密钥池是合法状态：acquire 返回 null，hasHealthyKey 为 false")
    void emptyPoolYieldsNull() {
        var pool = new KeyPool(List.of(), CONFIG);

        assertThat(pool.acquire()).isNull();
        assertThat(pool.hasHealthyKey()).isFalse();
        assertThat(pool.size()).isZero();
    }

    @Test
    @DisplayName("非法配置直接拒绝")
    void rejectsInvalidInput() {
        assertThatThrownBy(() -> new KeyPoolConfig(0, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KeyPoolConfig(1, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
