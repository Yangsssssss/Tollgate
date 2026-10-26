package io.tollgate.core.keypool;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 车道内的密钥池：轮换取用 + 连续失败封禁 + 到期自动解封。
 *
 * <p>一条车道可能挂多个上游密钥（不同账号、分摊限额）。密钥失效和车道失效是两个
 * 层次的问题：车道挂了走熔断器，密钥挂了只该影响本车道内的轮换，所以单独建池，
 * 不把密钥状态混进熔断器的统计里——否则一个坏密钥会把整条车道拖进熔断。
 *
 * <p><b>轮换方式</b>：原子游标递增 + 取模，无锁。不追求严格公平，游标回绕时
 * 可能轻微打破轮换顺序，但密钥间本就无序，无所谓。
 *
 * <p><b>封禁语义</b>：连续失败 {@code threshold} 次即封禁，封禁期内 {@link #acquire()}
 * 跳过该密钥；到期后自动恢复可被选中，由下一次真实调用验证。封禁是"连续失败"
 * 计数触发，任何一次成功都会清零计数——密钥问题往往是间歇性的（限流 429），
 * 不给恢复机会会造成误杀。
 */
public final class KeyPool {

    /**
     * 池内密钥。secret 是真实上游密钥，仅在调用上游时使用。
     *
     * <p>特意覆写 toString：record 自动生成的 toString 会带出 secret，
     * 一旦进了日志就是事故。这里只暴露 id。
     */
    public record LaneKey(long id, String secret) {
        @Override
        public String toString() {
            return "LaneKey[id=" + id + "]";
        }
    }

    private final LaneKey[] keys;
    private final AtomicInteger cursor = new AtomicInteger();
    private final AtomicInteger[] consecutiveFailures;
    private final AtomicLong[] bannedUntil;
    private final KeyPoolConfig config;
    private final LongSupplier clock;

    public KeyPool(List<LaneKey> keys, KeyPoolConfig config) {
        this(keys, config, System::currentTimeMillis);
    }

    /** 测试用：注入可控时钟 */
    public KeyPool(List<LaneKey> keys, KeyPoolConfig config, LongSupplier clock) {
        // 允许空列表：车道尚未配置密钥是合法的配置状态，acquire() 返回 null 即可，
        // 调用方（LaneSelector）据此跳过该车道。构造期抛异常反而会把配置问题
        // 变成运行期故障
        this.keys = keys == null ? new LaneKey[0] : keys.toArray(new LaneKey[0]);
        this.consecutiveFailures = new AtomicInteger[this.keys.length];
        this.bannedUntil = new AtomicLong[this.keys.length];
        for (int i = 0; i < this.keys.length; i++) {
            this.consecutiveFailures[i] = new AtomicInteger();
            this.bannedUntil[i] = new AtomicLong();
        }
        this.config = config;
        this.clock = clock;
    }

    /**
     * 轮换取一把可用密钥。
     *
     * @return 可用密钥；全部处于封禁期时返回 null，调用方应视为该车道暂不可用
     */
    public LaneKey acquire() {
        int n = keys.length;
        for (int i = 0; i < n; i++) {
            // getAndIncrement 在溢出时会变负，floorMod 保证索引仍合法
            int idx = Math.floorMod(cursor.getAndIncrement(), n);
            if (bannedUntil[idx].get() <= clock.getAsLong()) {
                return keys[idx];
            }
        }
        return null;
    }

    public void reportSuccess(LaneKey key) {
        int idx = indexOf(key);
        consecutiveFailures[idx].set(0);
        bannedUntil[idx].set(0);
    }

    public void reportFailure(LaneKey key) {
        int idx = indexOf(key);
        if (consecutiveFailures[idx].incrementAndGet() >= config.consecutiveFailureThreshold()) {
            long until = clock.getAsLong() + config.banDuration().toMillis();
            // 先写封禁时间再看计数：其他线程读到 bannedUntil 的最新值时必然有效
            bannedUntil[idx].set(until);
            consecutiveFailures[idx].set(0);
        }
    }

    /** 是否存在至少一把未被封禁的密钥，用于候选集预筛 */
    public boolean hasHealthyKey() {
        long now = clock.getAsLong();
        for (AtomicLong ban : bannedUntil) {
            if (ban.get() <= now) {
                return true;
            }
        }
        return false;
    }

    public int size() {
        return keys.length;
    }

    private int indexOf(LaneKey key) {
        for (int i = 0; i < keys.length; i++) {
            if (keys[i].id() == key.id()) {
                return i;
            }
        }
        throw new IllegalArgumentException("密钥不属于本池: " + key);
    }

    /** 便于观测：导出各密钥剩余封禁毫秒数 */
    public List<Long> banSnapshot() {
        long now = clock.getAsLong();
        return List.of(bannedUntil).stream()
                .map(ban -> Math.max(0, ban.get() - now))
                .toList();
    }
}
