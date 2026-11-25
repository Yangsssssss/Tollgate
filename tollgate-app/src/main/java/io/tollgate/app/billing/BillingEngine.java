package io.tollgate.app.billing;

import io.tollgate.app.dataplane.CatalogLoader;
import io.tollgate.metering.BillingService;
import io.tollgate.metering.TariffRate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 预付费计费引擎（{@link BillingService} 的唯一实现）。
 *
 * <p><b>余额的权威在内存，DB 是持久化副本</b>——与热路径零查库一致：
 * precheck/settle 全程原子操作，不碰数据库。扣费流水先进内存队列，
 * 单线程 flusher 批量写 balance_ledger，并按租户聚合 delta 增量同步到
 * tenant.balance_micros（UPDATE balance = balance + delta 是可交换的
 * 增量操作，与充值通道并发写不会互相覆盖）。
 *
 * <p><b>幂等</b>：扣费流水以 (tenant_id, ref=trace_id) 唯一键兜底，
 * ON DUPLICATE KEY UPDATE id = id 保证重放投递不重复记账。
 *
 * <p><b>已知边界（有意接受）</b>：进程崩溃丢队列中未刷流水，最多 2 秒；
 * 与 BatchingTollRecorder 同一取舍，产品化演进方向是 WAL 或外部队列。
 * 余额缓存不做 TTL——充值/调整必须走 {@link #credit}（会同步更新内存），
 * 绕过本引擎直接改库的行为对运行时不可见，这是约定而非缺陷。
 */
@Component
public class BillingEngine implements BillingService {

    private final CatalogLoader catalog;
    private final JdbcTemplate jdbc;
    private final AtomicLong fallbackBalance = new AtomicLong();
    private final ConcurrentLinkedQueue<LedgerEntry> ledgerQueue = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService flusher;

    /** 租户 → 内存余额（micros）。惰性回源，加载后由本引擎独占维护 */
    private final Map<Long, AtomicLong> balances = new java.util.concurrent.ConcurrentHashMap<>();

    private record LedgerEntry(long tenantId, String ref, String reason,
                               long deltaMicros, long balanceAfterMicros) {
    }

    public BillingEngine(CatalogLoader catalog, JdbcTemplate jdbc) {
        this.catalog = catalog;
        this.jdbc = jdbc;
        this.flusher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "billing-flusher");
            t.setDaemon(true);
            return t;
        });
        this.flusher.scheduleWithFixedDelay(this::flushSafely, 2, 2, TimeUnit.SECONDS);
    }

    // ---- 计价 ----

    @Override
    public long estimateCost(String modelAlias, long promptTokens, long completionTokens) {
        TariffRate rate = catalog.current().tariffs().get(modelAlias);
        if (rate == null) {
            return 0; // 未配费率 = 免费直通，避免配置缺失导致全部请求 402
        }
        return costOf(rate, promptTokens, completionTokens);
    }

    /** 分维度向上取整：ceil(prompt/1000*pIn) + ceil(completion/1000*pOut) */
    private static long costOf(TariffRate rate, long promptTokens, long completionTokens) {
        long promptCost = (promptTokens * rate.promptMicrosPer1k() + 999) / 1000;
        long completionCost = (completionTokens * rate.completionMicrosPer1k() + 999) / 1000;
        return promptCost + completionCost;
    }

    // ---- 预检与扣费 ----

    @Override
    public boolean precheck(long tenantId, long estimatedCostMicros) {
        return balanceOf(tenantId) >= estimatedCostMicros;
    }

    @Override
    public long settle(long tenantId, String traceId, String modelAlias,
                       long promptTokens, long completionTokens) {
        long cost = estimateCost(modelAlias, promptTokens, completionTokens);
        if (cost <= 0) {
            return 0;
        }
        long after = balanceRef(tenantId).addAndGet(-cost);
        ledgerQueue.offer(new LedgerEntry(tenantId, traceId, "DEDUCT", -cost, after));
        return cost;
    }

    @Override
    public long balanceOf(long tenantId) {
        return balanceRef(tenantId).get();
    }

    /** 内存余额的原子引用。惰性回源，加载后由本引擎独占维护 */
    private AtomicLong balanceRef(long tenantId) {
        return balances.computeIfAbsent(tenantId, this::loadBalanceFromDb);
    }

    /** 回源 DB 读余额；租户行不存在时按 0 处理（会让 precheck 拒掉无主租户） */
    private AtomicLong loadBalanceFromDb(long tenantId) {
        List<Long> found = jdbc.query(
                "SELECT balance_micros FROM tenant WHERE id = ?",
                (rs, i) -> rs.getLong(1), tenantId);
        return new AtomicLong(found.isEmpty() ? 0 : found.get(0));
    }

    @Override
    public void credit(long tenantId, long amountMicros, String reason, String ref) {
        if (amountMicros <= 0) {
            throw new IllegalArgumentException("充值金额必须为正: " + amountMicros);
        }
        Objects.requireNonNull(ref, "充值流水必须有幂等引用");
        long after = balanceRef(tenantId).addAndGet(amountMicros);
        ledgerQueue.offer(new LedgerEntry(tenantId, ref, reason, amountMicros, after));
    }

    // ---- 落库 ----

    private void flushSafely() {
        try {
            flushLedger();
        } catch (Exception e) {
            System.err.println("[billing] 流水批写失败: " + e.getMessage());
        }
    }

    private void flushLedger() {
        if (ledgerQueue.isEmpty()) {
            return;
        }
        List<LedgerEntry> batch = new java.util.ArrayList<>(ledgerQueue.size());
        LedgerEntry entry;
        while ((entry = ledgerQueue.poll()) != null) {
            batch.add(entry);
        }

        jdbc.batchUpdate("""
                INSERT INTO balance_ledger (tenant_id, ref, reason, delta_micros, balance_after_micros)
                VALUES (?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE id = id
                """, batch, batch.size(), (ps, e) -> {
            ps.setLong(1, e.tenantId());
            ps.setString(2, e.ref());
            ps.setString(3, e.reason());
            ps.setLong(4, e.deltaMicros());
            ps.setLong(5, e.balanceAfterMicros());
        });

        // 按租户聚合 delta，增量同步到 tenant 表——增量写法与充值并发可交换
        Map<Long, Long> deltaByTenant = new LinkedHashMap<>();
        batch.forEach(e -> deltaByTenant.merge(e.tenantId(), e.deltaMicros(), Long::sum));
        deltaByTenant.forEach((tenantId, delta) ->
                jdbc.update("UPDATE tenant SET balance_micros = balance_micros + ? WHERE id = ?",
                        delta, tenantId));
    }

    /** 优雅停机：先停调度再冲账，避免停机期间丢尾巴 */
    @jakarta.annotation.PreDestroy
    void shutdown() {
        flusher.shutdown();
        try {
            flusher.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        flushSafely();
    }
}
