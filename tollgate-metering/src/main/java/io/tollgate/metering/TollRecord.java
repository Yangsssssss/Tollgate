package io.tollgate.metering;

import io.tollgate.common.model.TokenUsage;

import java.time.Instant;

/**
 * 一次调用的完整计量记录，是计费的唯一凭据。
 *
 * <p>关键设计：{@code usage} 与 {@code costMicros} 分开存。上游价格调整时不回溯历史成本，
 * 计费口径以落账时刻的费率快照为准。
 *
 * @param traceId        链路 ID，与 request_log 关联
 * @param tenantId       租户
 * @param modelAlias     对外模型别名
 * @param laneCode       实际服务的车道
 * @param usage          token 用量
 * @param costMicros     成本，单位百万分之一元，避免浮点误差
 * @param latencyMillis  端到端耗时
 * @param retryCount     该请求换道重试次数
 * @param success        是否成功
 * @param occurredAt     事件发生时刻
 */
public record TollRecord(
        String traceId,
        long tenantId,
        String modelAlias,
        String laneCode,
        TokenUsage usage,
        long costMicros,
        long latencyMillis,
        int retryCount,
        boolean success,
        Instant occurredAt
) {
}
