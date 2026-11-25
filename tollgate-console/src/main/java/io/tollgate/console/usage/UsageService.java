package io.tollgate.console.usage;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 用量查询：toll_record 的只读聚合。查询走 JDBC（阻塞），
 * 由 WebFlux 的 boundedElastic 调度器承接（Router 侧 subscribeOn）。
 */
@Service
public class UsageService {

    private final JdbcTemplate jdbc;

    public UsageService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record DailyUsage(String date, long calls, long tokens, long costMicros) {
    }

    public record ModelUsage(String modelAlias, long calls, long tokens, long costMicros) {
    }

    public record UsageReport(long calls, long tokens, long costMicros,
                              List<DailyUsage> daily, List<ModelUsage> byModel,
                              List<Map<String, Object>> recent) {
    }

    public UsageReport report(long tenantId, int days) {
        int safeDays = Math.clamp(days, 1, 90);

        Long calls = jdbc.queryForObject("""
                SELECT COUNT(*) FROM toll_record
                WHERE tenant_id = ? AND success = TRUE
                  AND occurred_at > NOW() - INTERVAL ? DAY
                """, Long.class, tenantId, safeDays);
        Long tokens = jdbc.queryForObject("""
                SELECT COALESCE(SUM(prompt_tokens + completion_tokens), 0) FROM toll_record
                WHERE tenant_id = ? AND success = TRUE
                  AND occurred_at > NOW() - INTERVAL ? DAY
                """, Long.class, tenantId, safeDays);
        Long cost = jdbc.queryForObject("""
                SELECT COALESCE(SUM(cost_micros), 0) FROM toll_record
                WHERE tenant_id = ? AND success = TRUE
                  AND occurred_at > NOW() - INTERVAL ? DAY
                """, Long.class, tenantId, safeDays);

        List<DailyUsage> daily = jdbc.query("""
                SELECT DATE(occurred_at) AS d, COUNT(*) AS calls,
                       SUM(prompt_tokens + completion_tokens) AS tokens,
                       SUM(cost_micros) AS cost
                FROM toll_record
                WHERE tenant_id = ? AND success = TRUE
                  AND occurred_at > NOW() - INTERVAL ? DAY
                GROUP BY d ORDER BY d
                """, (rs, i) -> new DailyUsage(
                rs.getDate("d").toString(), rs.getLong("calls"),
                rs.getLong("tokens"), rs.getLong("cost")), tenantId, safeDays);

        List<ModelUsage> byModel = jdbc.query("""
                SELECT model_alias, COUNT(*) AS calls,
                       SUM(prompt_tokens + completion_tokens) AS tokens,
                       SUM(cost_micros) AS cost
                FROM toll_record
                WHERE tenant_id = ? AND success = TRUE
                  AND occurred_at > NOW() - INTERVAL ? DAY
                GROUP BY model_alias ORDER BY cost DESC
                """, (rs, i) -> new ModelUsage(
                rs.getString("model_alias"), rs.getLong("calls"),
                rs.getLong("tokens"), rs.getLong("cost")), tenantId, safeDays);

        List<Map<String, Object>> recent = jdbc.queryForList("""
                SELECT trace_id, model_alias, lane_code, prompt_tokens, completion_tokens,
                       estimated, cost_micros, latency_millis, occurred_at
                FROM toll_record
                WHERE tenant_id = ? AND success = TRUE
                ORDER BY occurred_at DESC LIMIT 20
                """, tenantId);

        return new UsageReport(calls, tokens, cost, daily, byModel, recent);
    }

    public List<Map<String, Object>> recentLedger(long tenantId, int limit) {
        return jdbc.queryForList("""
                SELECT ref, reason, delta_micros, balance_after_micros, created_at
                FROM balance_ledger WHERE tenant_id = ?
                ORDER BY created_at DESC LIMIT ?
                """, tenantId, Math.clamp(limit, 1, 200));
    }

    public List<Map<String, Object>> tariffs() {
        return jdbc.queryForList("""
                SELECT model_alias, prompt_micros_per_1k, completion_micros_per_1k
                FROM tariff WHERE status = 'ENABLED' ORDER BY model_alias
                """);
    }
}
