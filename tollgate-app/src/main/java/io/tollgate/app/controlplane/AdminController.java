package io.tollgate.app.controlplane;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tollgate.app.dataplane.CatalogLoader;
import io.tollgate.app.dataplane.DispatchRuntime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 控制面管理 API 的处理器。职责：配置 CRUD + 观测数据出口。
 *
 * <p><b>线程模型</b>：所有 JDBC 操作经由 boundedElastic 执行——控制面是低频
 * 管理操作，不需要响应式化，阻塞池隔离即可保证不干扰数据面的事件循环。
 *
 * <p><b>生效路径</b>：写库后由 CatalogLoader 的 30 秒周期刷新（或手动
 * POST /admin/refresh）拉进快照，数据面无感热换。这就是"控制面/数据面
 * 通过快照解耦"的完整闭环：控制面永远不直接触碰调度内核的状态。
 *
 * <p>V1 演示版未加管理端鉴权；生产化时补 mTLS 或独立管理端口 + Token。
 */
@Component
public class AdminController {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final CatalogLoader catalog;
    private final DispatchRuntime runtime;
    private final ObjectMapper mapper;

    public AdminController(JdbcTemplate jdbc, CatalogLoader catalog,
                           DispatchRuntime runtime, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.runtime = runtime;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------
    // 车道
    // ------------------------------------------------------------------

    /** 车道列表，附带熔断器实时状态——观测与排障的主入口 */
    public Mono<ServerResponseHolder> listLanes() {
        return blocking(() -> {
            List<Map<String, Object>> lanes = jdbc.queryForList("""
                    SELECT l.id, l.code, p.code AS provider_code, p.protocol, l.base_url,
                           l.priority, l.weight, l.status, l.timeout_millis,
                           (SELECT COUNT(*) FROM lane_key k
                            WHERE k.lane_id = l.id AND k.status = 'ACTIVE') AS active_keys
                    FROM lane l JOIN provider p ON p.id = l.provider_id
                    ORDER BY l.priority, l.id
                    """);
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> lane : lanes) {
                long laneId = ((Number) lane.get("id")).longValue();
                var breaker = runtime.breakerOf(laneId);
                lane.put("breaker", breaker == null
                        ? Map.of("state", "NEVER_USED") : breakerView(breaker));
                out.add(lane);
            }
            return ok(Map.of("lanes", out));
        });
    }

    /** 创建车道。provider 按 code 解析；可选携带 upstreamKeys 一次灌入密钥池 */
    public Mono<ServerResponseHolder> createLane(JsonNode body) {
        return blocking(() -> {
            String code = required(body, "code");
            String providerCode = required(body, "providerCode");
            String baseUrl = required(body, "baseUrl");
            Long providerId = jdbc.queryForObject(
                    "SELECT id FROM provider WHERE code = ?", Long.class, providerCode);
            if (providerId == null) {
                return bad("provider 不存在: " + providerCode);
            }
            // MySQL 不支持 INSERT...RETURNING，用 GeneratedKeyHolder 取自增主键
            var keyHolder = new org.springframework.jdbc.support.GeneratedKeyHolder();
            jdbc.update(con -> {
                var ps = con.prepareStatement("""
                        INSERT INTO lane (code, provider_id, base_url, priority, weight, timeout_millis)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """, java.sql.Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, code);
                ps.setLong(2, providerId);
                ps.setString(3, baseUrl);
                ps.setInt(4, body.path("priority").asInt(100));
                ps.setInt(5, body.path("weight").asInt(1));
                ps.setLong(6, body.path("timeoutMillis").asLong(120_000));
                return ps;
            }, keyHolder);
            long laneId = keyHolder.getKey().longValue();
            for (JsonNode key : body.path("upstreamKeys")) {
                jdbc.update("INSERT INTO lane_key (lane_id, secret) VALUES (?, ?)", laneId, key.asText());
            }
            return ok(Map.of("id", laneId, "message", "创建成功，等待快照刷新后生效"));
        });
    }

    /** 更新车道：baseUrl / priority / weight / status 任意子集 */
    public Mono<ServerResponseHolder> updateLane(long laneId, JsonNode body) {
        return blocking(() -> {
            List<String> sets = new ArrayList<>();
            List<Object> args = new ArrayList<>();
            if (body.hasNonNull("baseUrl")) {
                sets.add("base_url = ?");
                args.add(body.get("baseUrl").asText());
            }
            if (body.has("priority")) {
                sets.add("priority = ?");
                args.add(body.get("priority").asInt());
            }
            if (body.has("weight")) {
                sets.add("weight = ?");
                args.add(body.get("weight").asInt());
            }
            if (body.hasNonNull("status")) {
                String status = body.get("status").asText();
                if (!status.equals("ENABLED") && !status.equals("DISABLED")) {
                    return bad("status 只允许 ENABLED / DISABLED");
                }
                sets.add("status = ?");
                args.add(status);
            }
            if (sets.isEmpty()) {
                return bad("无可更新字段");
            }
            args.add(laneId);
            int updated = jdbc.update(
                    "UPDATE lane SET " + String.join(", ", sets) + " WHERE id = ?", args.toArray());
            return updated == 0 ? bad("车道不存在: " + laneId)
                    : ok(Map.of("message", "已更新，等待快照刷新后生效"));
        });
    }

    // ------------------------------------------------------------------
    // 模型路由
    // ------------------------------------------------------------------

    public Mono<ServerResponseHolder> listRoutes() {
        return blocking(() -> {
            List<Map<String, Object>> routes = jdbc.queryForList("""
                    SELECT m.alias, ml.lane_id, l.code AS lane_code, ml.upstream_model,
                           ml.weight_override, ml.enabled
                    FROM model m
                    JOIN model_lane ml ON ml.model_id = m.id
                    JOIN lane l ON l.id = ml.lane_id
                    ORDER BY m.alias, ml.lane_id
                    """);
            return ok(Map.of("routes", routes));
        });
    }

    /**
     * 配置路由：model 不存在则创建别名，model_lane 冲突则更新。
     * 这一个端点覆盖"新增模型 + 挂车道 + 改上游名"三类操作。
     */
    public Mono<ServerResponseHolder> upsertRoute(JsonNode body) {
        return blocking(() -> {
            String alias = required(body, "modelAlias");
            long laneId = body.path("laneId").asLong(0);
            String upstreamModel = required(body, "upstreamModel");
            if (laneId <= 0) {
                return bad("laneId 必填");
            }
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM lane WHERE id = ?", Integer.class, laneId);
            if (count == null || count == 0) {
                return bad("车道不存在: " + laneId);
            }
            jdbc.update("""
                    INSERT INTO model (alias) VALUES (?)
                    ON DUPLICATE KEY UPDATE alias = alias
                    """, alias);
            Long modelId = jdbc.queryForObject(
                    "SELECT id FROM model WHERE alias = ?", Long.class, alias);
            Integer weightOverride = body.has("weightOverride") && !body.get("weightOverride").isNull()
                    ? body.get("weightOverride").asInt() : null;
            jdbc.update("""
                    INSERT INTO model_lane (model_id, lane_id, upstream_model, weight_override, enabled)
                    VALUES (?, ?, ?, ?, TRUE)
                    ON DUPLICATE KEY UPDATE upstream_model = VALUES(upstream_model),
                                            weight_override = VALUES(weight_override),
                                            enabled = TRUE
                    """, modelId, laneId, upstreamModel, weightOverride);
            return ok(Map.of("message", "路由已配置，等待快照刷新后生效"));
        });
    }

    public Mono<ServerResponseHolder> disableRoute(String alias, long laneId) {
        return blocking(() -> {
            int updated = jdbc.update("""
                    UPDATE model_lane ml JOIN model m ON m.id = ml.model_id
                    SET ml.enabled = FALSE
                    WHERE m.alias = ? AND ml.lane_id = ?
                    """, alias, laneId);
            return updated == 0 ? bad("路由不存在: " + alias + " -> lane " + laneId)
                    : ok(Map.of("message", "路由已下线"));
        });
    }

    // ------------------------------------------------------------------
    // API 密钥
    // ------------------------------------------------------------------

    public Mono<ServerResponseHolder> listApiKeys() {
        return blocking(() -> {
            List<Map<String, Object>> keys = jdbc.queryForList("""
                    SELECT k.id, t.code AS tenant_code, k.name, k.key_prefix, k.status,
                           k.expires_at, k.created_at
                    FROM api_key k JOIN tenant t ON t.id = k.tenant_id
                    ORDER BY k.id
                    """);
            return ok(Map.of("keys", keys));
        });
    }

    /**
     * 签发密钥。明文只在本次响应出现一次，库内只有 SHA-256——
     * 与上游云厂商密钥管理的行为对齐，丢了这个响应就只能吊销重发。
     */
    public Mono<ServerResponseHolder> createApiKey(JsonNode body) {
        return blocking(() -> {
            String tenantCode = required(body, "tenantCode");
            String name = required(body, "name");
            Long tenantId = jdbc.queryForObject(
                    "SELECT id FROM tenant WHERE code = ?", Long.class, tenantCode);
            if (tenantId == null) {
                return bad("tenant 不存在: " + tenantCode);
            }
            byte[] raw = new byte[15];
            RANDOM.nextBytes(raw);
            String plaintext = "tg-" + HexFormat.of().formatHex(raw);
            String hash = sha256Hex(plaintext);
            jdbc.update("""
                    INSERT INTO api_key (tenant_id, name, key_prefix, key_hash)
                    VALUES (?, ?, ?, ?)
                    """, tenantId, name, plaintext.substring(0, 10), hash);
            return ok(Map.of(
                    "key", plaintext,
                    "prefix", plaintext.substring(0, 10),
                    "warning", "明文仅此一次展示，请立即保存"));
        });
    }

    public Mono<ServerResponseHolder> disableApiKey(long keyId) {
        return blocking(() -> {
            int updated = jdbc.update(
                    "UPDATE api_key SET status = 'DISABLED' WHERE id = ?", keyId);
            return updated == 0 ? bad("密钥不存在: " + keyId)
                    : ok(Map.of("message", "已吊销，最迟一个刷新周期后失效"));
        });
    }

    // ------------------------------------------------------------------
    // 观测与运维
    // ------------------------------------------------------------------

    /** 手动触发快照刷新，返回刷新后的配置规模。改完配置不想等 30 秒就调它 */
    public Mono<ServerResponseHolder> refresh() {
        return blocking(() -> {
            catalog.refresh();
            var snapshot = catalog.current();
            return ok(Map.of(
                    "lanes", snapshot.lanes().size(),
                    "models", snapshot.routes().size(),
                    "credentials", snapshot.credentials().size(),
                    "laneKeys", snapshot.laneSecrets().values().stream()
                            .mapToInt(List::size).sum()));
        });
    }

    /** 全部熔断器状态快照（包含从未被使用过的车道） */
    public Mono<ServerResponseHolder> breakers() {
        return blocking(() -> {
            Map<String, Object> out = new LinkedHashMap<>();
            catalog.current().lanes().forEach((laneId, lane) -> {
                var breaker = runtime.breakerOf(laneId);
                out.put(lane.code(), breaker == null
                        ? Map.of("state", "NEVER_USED") : breakerView(breaker));
            });
            return ok(out);
        });
    }

    /** 近 100 条调用明细，排障用 */
    public Mono<ServerResponseHolder> recentRequests() {
        return blocking(() -> {
            List<Map<String, Object>> logs = jdbc.queryForList("""
                    SELECT trace_id, lane_code, attempt, status, http_status, error_code,
                           latency_millis, streamed, created_at
                    FROM request_log ORDER BY id DESC LIMIT 100
                    """);
            return ok(Map.of("logs", logs));
        });
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private Map<String, Object> breakerView(io.tollgate.core.breaker.LaneCircuitBreaker b) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("state", b.state().name());
        view.put("failureRate", String.format("%.2f", b.currentFailureRate()));
        view.put("sampleCount", b.currentSampleCount());
        view.put("tripCount", b.tripCount());
        view.put("rejectedCount", b.rejectedCount());
        view.put("remainingOpenMillis", b.remainingOpenMillis());
        return view;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String required(JsonNode body, String field) {
        String value = body.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("字段必填: " + field);
        }
        return value;
    }

    private ServerResponseHolder ok(Map<String, Object> data) {
        return new ServerResponseHolder(200, toJson(data));
    }

    private ServerResponseHolder bad(String message) {
        return new ServerResponseHolder(400, toJson(Map.of("error", message)));
    }

    private String toJson(Object data) {
        try {
            return mapper.writeValueAsString(data);
        } catch (Exception e) {
            return "{\"error\":\"serialize failed\"}";
        }
    }

    private Mono<ServerResponseHolder> blocking(java.util.function.Supplier<ServerResponseHolder> work) {
        return Mono.fromCallable(work::get).subscribeOn(Schedulers.boundedElastic());
    }

    /** 处理结果载体：状态码 + 已序列化的 JSON 体 */
    public record ServerResponseHolder(int status, String body) {
    }
}
