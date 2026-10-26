package io.tollgate.app.metering;

import io.tollgate.common.model.TokenUsage;
import io.tollgate.metering.TollRecord;
import io.tollgate.metering.TollRecorder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 攒批落账器：计量记录先入内存队列，按条数阈值或时间间隔批量写库。
 *
 * <p><b>为什么攒批</b>：计量是每请求一写的高频小事务，逐条 INSERT 会让 MySQL
 * 的组提交压力全来自它。攒 200 条一批，写放大降两个数量级。
 *
 * <p><b>可靠性取舍（有意为之）</b>：进程崩溃丢队列里未刷的账，最多丢 2 秒。
 * 计费场景的正确做法是本地持久化队列（WAL）+ 重放，V1.3 先接受丢失——
 * 这是个只读演示环境，为了 2 秒账单引入文件持久化不值得，但取舍要写在这里：
 * <b>如果你要把这个项目讲给面试官，"崩溃丢账怎么解"就是必答题</b>：
 * 答 WAL 或改用 Kafka/Redis Stream 做投递层。
 *
 * <p><b>幂等</b>：toll_record 有 (trace_id, attempt) 唯一键，批写用
 * ON DUPLICATE KEY UPDATE id = id，重放投递不会记双份账。
 */
@Component
public class BatchingTollRecorder implements TollRecorder {

    private final JdbcTemplate jdbc;
    private final int batchSize;
    private final ScheduledExecutorService flusher;
    private final ConcurrentLinkedQueue<TollRecord> tollQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<RequestLogEntry> logQueue = new ConcurrentLinkedQueue<>();

    public BatchingTollRecorder(JdbcTemplate jdbc,
                                @Value("${tollgate.metering.batch-size:200}") int batchSize,
                                @Value("${tollgate.metering.flush-interval-seconds:2}") long flushSeconds) {
        this.jdbc = jdbc;
        this.batchSize = batchSize;
        this.flusher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "toll-flusher");
            t.setDaemon(true);
            return t;
        });
        this.flusher.scheduleWithFixedDelay(this::flushSafely, flushSeconds, flushSeconds, TimeUnit.SECONDS);
    }

    /** 一次 attempt 的调用明细 */
    public record RequestLogEntry(
            String traceId, long tenantId, long apiKeyId, String modelAlias,
            String laneCode, int attempt, String status, Integer httpStatus,
            String errorCode, long latencyMillis, boolean streamed
    ) {
    }

    @Override
    public void record(TollRecord toll) {
        // 契约：非阻塞、永不抛。队列无界是可接受的——单条 record 约 200B，
        // 即使下游故障积压 100 万条也只有 ~200MB，且 flusher 常驻会持续消化
        tollQueue.offer(toll);
        if (tollQueue.size() >= batchSize) {
            // 必须交给 flusher 线程执行：record 的调用方是 Netty 事件循环，
            // 在它上面跑阻塞 JDBC 等于掐住整个网关的吞吐
            flusher.execute(this::flushSafely);
        }
    }

    public void logRequest(RequestLogEntry entry) {
        logQueue.offer(entry);
        if (logQueue.size() >= batchSize) {
            flusher.execute(this::flushSafely);
        }
    }

    private void flushSafely() {
        try {
            flushToll();
        } catch (Exception e) {
            System.err.println("[metering] toll_record 批写失败, 丢弃 " + " 一批: " + e.getMessage());
        }
        try {
            flushLog();
        } catch (Exception e) {
            System.err.println("[metering] request_log 批写失败: " + e.getMessage());
        }
    }

    private void flushToll() {
        List<TollRecord> batch = drain(tollQueue);
        if (batch.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                INSERT INTO toll_record (trace_id, attempt, tenant_id, model_alias, lane_code,
                        prompt_tokens, completion_tokens, estimated, cost_micros,
                        latency_millis, success, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?)
                ON DUPLICATE KEY UPDATE id = id
                """, batch, batch.size(), (ps, toll) -> {
            TokenUsage usage = toll.usage();
            ps.setString(1, toll.traceId());
            ps.setInt(2, toll.retryCount() + 1); // attempt 从 1 计
            ps.setLong(3, toll.tenantId());
            ps.setString(4, toll.modelAlias());
            ps.setString(5, toll.laneCode());
            ps.setInt(6, usage.promptTokens());
            ps.setInt(7, usage.completionTokens());
            ps.setBoolean(8, usage.estimated());
            ps.setLong(9, toll.latencyMillis());
            ps.setBoolean(10, toll.success());
            ps.setTimestamp(11, Timestamp.from(toll.occurredAt()));
        });
    }

    private void flushLog() {
        List<RequestLogEntry> batch = drain(logQueue);
        if (batch.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                INSERT INTO request_log (trace_id, tenant_id, api_key_id, model_alias, lane_code,
                        attempt, status, http_status, error_code, latency_millis, streamed)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, batch, batch.size(), (ps, entry) -> {
            ps.setString(1, entry.traceId());
            ps.setLong(2, entry.tenantId());
            ps.setLong(3, entry.apiKeyId());
            ps.setString(4, entry.modelAlias());
            ps.setString(5, entry.laneCode());
            ps.setInt(6, entry.attempt());
            ps.setString(7, entry.status());
            if (entry.httpStatus() != null) {
                ps.setInt(8, entry.httpStatus());
            } else {
                ps.setNull(8, java.sql.Types.INTEGER);
            }
            ps.setString(9, entry.errorCode());
            ps.setLong(10, entry.latencyMillis());
            ps.setBoolean(11, entry.streamed());
        });
    }

    private static <T> List<T> drain(ConcurrentLinkedQueue<T> queue) {
        List<T> out = new ArrayList<>(queue.size());
        T item;
        while ((item = queue.poll()) != null) {
            out.add(item);
        }
        return out;
    }

    @PreDestroy
    void shutdown() {
        flusher.shutdown();
        try {
            flusher.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        flushSafely(); // 优雅停机时把尾巴刷掉
    }
}
