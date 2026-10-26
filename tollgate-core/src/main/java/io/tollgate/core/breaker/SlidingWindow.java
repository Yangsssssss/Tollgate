package io.tollgate.core.breaker;

import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

/**
 * 环形分桶滑动窗口计数器。
 *
 * <p>为什么不用固定窗口：固定窗口在边界处会有"双倍流量"问题——窗口切换瞬间，
 * 前后两个窗口各自放行满额流量，实际瞬时值是阈值的两倍。滑动窗口把时间切成多个桶，
 * 每次统计只看最近的若干个桶，边界效应被摊薄。
 *
 * <p>并发策略：用 {@code synchronized} 保护桶数组。临界区只有一次下标计算和两次自增，
 * 纳秒级，且同一车道的调用分散在多个线程上时竞争极低。若压测显示这是瓶颈，
 * 可按车道分片或改用 {@code AtomicLongArray} + 版本号重试——但先不要提前优化。
 *
 * <p>容量上限：每个桶独立计数，写入前先判断桶是否过期，过期则重置。这样无需定时清理任务。
 */
public final class SlidingWindow {

    private final long bucketMillis;
    private final int bucketCount;
    private final LongSupplier clock;

    private final LongAdder[] successes;
    private final LongAdder[] failures;
    /** 每个桶归属的时间片编号，用于判断桶是否已过期 */
    private final long[] bucketEpochs;

    public SlidingWindow(CircuitBreakerConfig config) {
        this(config, System::currentTimeMillis);
    }

    /** 测试用：注入可控时钟 */
    public SlidingWindow(CircuitBreakerConfig config, LongSupplier clock) {
        this.bucketCount = config.bucketCount();
        this.bucketMillis = Math.max(1, config.windowDuration().toMillis() / config.bucketCount());
        this.clock = clock;
        this.successes = new LongAdder[bucketCount];
        this.failures = new LongAdder[bucketCount];
        this.bucketEpochs = new long[bucketCount];
        for (int i = 0; i < bucketCount; i++) {
            successes[i] = new LongAdder();
            failures[i] = new LongAdder();
            bucketEpochs[i] = Long.MIN_VALUE;
        }
    }

    public void recordSuccess() {
        Bucket b = bucketOf();
        b.successes().increment();
    }

    public void recordFailure() {
        Bucket b = bucketOf();
        b.failures().increment();
    }

    /**
     * 重新开窗。熔断恢复时调用，避免把打开期间的陈旧统计带进新窗口。
     */
    public synchronized void reset() {
        for (int i = 0; i < bucketCount; i++) {
            successes[i].reset();
            failures[i].reset();
            bucketEpochs[i] = Long.MIN_VALUE;
        }
    }

    public synchronized long successCount() {
        long now = clock.getAsLong();
        long sum = 0;
        for (int i = 0; i < bucketCount; i++) {
            if (isLive(i, now)) {
                sum += successes[i].sum();
            }
        }
        return sum;
    }

    public synchronized long failureCount() {
        long now = clock.getAsLong();
        long sum = 0;
        for (int i = 0; i < bucketCount; i++) {
            if (isLive(i, now)) {
                sum += failures[i].sum();
            }
        }
        return sum;
    }

    public synchronized long totalCount() {
        return successCount() + failureCount();
    }

    /**
     * 失败率。窗口内无样本时返回 0，交由调用方用 minimumCalls 判断是否具备决策资格。
     */
    public synchronized double failureRate() {
        long success = successCount();
        long failure = failureCount();
        long total = success + failure;
        return total == 0 ? 0.0 : (double) failure / total;
    }

    private Bucket bucketOf() {
        long now = clock.getAsLong();
        long epoch = now / bucketMillis;
        int index = (int) Math.floorMod(epoch, (long) bucketCount);
        synchronized (this) {
            if (bucketEpochs[index] != epoch) {
                // 该槽位属于更早的时间片，重置后复用
                bucketEpochs[index] = epoch;
                successes[index].reset();
                failures[index].reset();
            }
        }
        return new Bucket(successes[index], failures[index]);
    }

    private boolean isLive(int index, long now) {
        long epoch = now / bucketMillis;
        // 桶的时间片编号与当前时间片之差在窗口跨度内才算有效
        return bucketEpochs[index] != Long.MIN_VALUE && bucketEpochs[index] > epoch - bucketCount;
    }

    private record Bucket(LongAdder successes, LongAdder failures) {
    }
}
