package io.tollgate.app.dataplane;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 配置目录加载器：把控制面数据（车道/路由/密钥/租户）整表拉进内存，
 * 组装成不可变快照。定时刷新，控制面改配置后最迟一个周期生效。
 *
 * <p><b>全量加载的理由</b>：配置量级是"几十车道 + 几百路由 + 几百密钥"，
 * 全表读一次是毫秒级、几十 KB 内存。增量同步（binlog / 变更时间戳）的复杂度
 * 在这个量级不划算——等配置涨到万级再换。
 *
 * <p><b>失败语义</b>：刷新失败保留旧快照继续服务，配置正确性宁可陈旧不可丢失。
 * 启动时加载失败则直接抛异常终止——空目录的网关只会把请求打成一个错误，
 * 不如不启动。
 */
@Component
public class CatalogLoader {

    private final JdbcTemplate jdbc;
    private final AtomicReference<CatalogSnapshot> snapshot = new AtomicReference<>();
    /** 快照更新后的下游联动（如限流器阈值重建）；监听器抛异常不影响刷新本身 */
    private final List<Runnable> refreshListeners = new CopyOnWriteArrayList<>();

    public CatalogLoader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        refresh();
    }

    /** 注册快照刷新回调，刷新成功后同步执行 */
    public void onRefresh(Runnable listener) {
        refreshListeners.add(listener);
    }

    public CatalogSnapshot current() {
        return snapshot.get();
    }

    /** 每 30 秒全量刷新一次；失败只记日志，旧快照继续服务 */
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void refreshScheduled() {
        try {
            refresh();
        } catch (Exception e) {
            // 计量与配置刷新的失败不能打断任何请求路径，吞掉并留痕
            System.err.println("[catalog] 刷新失败，沿用旧快照: " + e.getMessage());
        }
    }

    public void refresh() {
        Map<Long, CatalogSnapshot.CatalogLane> lanes = new LinkedHashMap<>();
        jdbc.query("""
                SELECT l.id, l.code, p.code AS provider_code, p.protocol, l.base_url,
                       l.priority, l.weight, l.timeout_millis
                FROM lane l JOIN provider p ON p.id = l.provider_id
                WHERE l.status = 'ENABLED'
                """, rs -> {
            var lane = new CatalogSnapshot.CatalogLane(
                    rs.getLong("id"), rs.getString("code"), rs.getString("provider_code"),
                    rs.getString("protocol"), rs.getString("base_url"),
                    rs.getInt("priority"), rs.getInt("weight"),
                    rs.getLong("timeout_millis"));
            lanes.put(lane.id(), lane);
        });

        Map<String, List<CatalogSnapshot.ModelRoute>> routes = new HashMap<>();
        jdbc.query("""
                SELECT m.alias, ml.lane_id, ml.upstream_model, ml.weight_override
                FROM model_lane ml
                JOIN model m ON m.id = ml.model_id AND m.status = 'ENABLED'
                WHERE ml.enabled = TRUE
                """, rs -> {
            routes.computeIfAbsent(rs.getString("alias"), k -> new ArrayList<>())
                    .add(new CatalogSnapshot.ModelRoute(
                            rs.getLong("lane_id"), rs.getString("upstream_model"),
                            (Integer) rs.getObject("weight_override")));
        });

        Map<String, CatalogSnapshot.ApiKeyCredential> credentials = new HashMap<>();
        jdbc.query("""
                SELECT k.id, k.key_hash, t.id AS tenant_id, t.code AS tenant_code,
                       t.rpm_limit, t.tpm_limit
                FROM api_key k JOIN tenant t ON t.id = k.tenant_id
                WHERE k.status = 'ACTIVE' AND t.status = 'ACTIVE'
                  AND (k.expires_at IS NULL OR k.expires_at > NOW())
                """, rs -> {
            var cred = new CatalogSnapshot.ApiKeyCredential(
                    rs.getLong("id"), rs.getLong("tenant_id"), rs.getString("tenant_code"),
                    rs.getInt("rpm_limit"), rs.getLong("tpm_limit"));
            credentials.put(rs.getString("key_hash"), cred);
        });

        Map<Long, List<String>> laneSecrets = new HashMap<>();
        jdbc.query("""
                SELECT lane_id, secret FROM lane_key
                WHERE status = 'ACTIVE'
                """, rs -> {
            laneSecrets.computeIfAbsent(rs.getLong("lane_id"), k -> new ArrayList<>())
                    .add(rs.getString("secret"));
        });

        snapshot.set(new CatalogSnapshot(
                Map.copyOf(lanes), Map.copyOf(routes), credentials, Map.copyOf(laneSecrets)));

        // 监听器异常只留痕，不能把已成功的刷新判成失败
        for (Runnable listener : refreshListeners) {
            try {
                listener.run();
            } catch (Exception e) {
                System.err.println("[catalog] 刷新回调失败: " + e.getMessage());
            }
        }
    }
}
