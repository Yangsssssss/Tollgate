package io.tollgate.app.dataplane;

import java.util.List;
import java.util.Map;

/**
 * 配置目录的不可变快照。数据面热路径对它的全部访问都是读，
 * 刷新时整体替换引用（AtomicReference.swap），读方永远看到一致的视图。
 *
 * <p>这是"热路径零查库"的落地：请求路径上不碰 MySQL，配置变更由
 * 定时刷新（{@link CatalogLoader}）异步搬进内存。
 *
 * @param lanes        全部启用车道，按 lane.id 可查
 * @param routes       模型别名 → 该模型可走的路由（车道 + 上游模型名）
 * @param credentials  密钥 SHA-256 → 凭据（含租户与限流额度）
 * @param laneSecrets  车道 → 上游密钥明文列表（仅存内存，不落日志）
 */
public record CatalogSnapshot(
        Map<Long, CatalogLane> lanes,
        Map<String, List<ModelRoute>> routes,
        Map<String, ApiKeyCredential> credentials,
        Map<Long, List<String>> laneSecrets
) {

    /** 车道配置的只读投影，字段与 lane 表一一对应 */
    public record CatalogLane(
            long id,
            String code,
            String providerCode,
            String protocol,
            String baseUrl,
            int priority,
            int weight,
            long timeoutMillis
    ) {
    }

    /**
     * 模型到车道的路由。
     *
     * @param laneId        车道
     * @param upstreamModel 上游认的模型名
     * @param weightOverride 权重覆盖，null 表示继承车道自身权重
     */
    public record ModelRoute(long laneId, String upstreamModel, Integer weightOverride) {
    }

    /** 对外密钥的凭据视图。keyHash 是 SHA-256 hex，明文不存在于内存任何地方 */
    public record ApiKeyCredential(
            long keyId,
            long tenantId,
            String tenantCode,
            int rpmLimit,
            long tpmLimit
    ) {
    }
}
