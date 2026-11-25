package io.tollgate.metering;

/**
 * 预付费计费服务。数据面（扣费）与控制台（余额/充值）共同依赖的抽象，
 * 实现在 app 层（BillingEngine）：内存缓存为运行时权威，DB 为持久化副本。
 *
 * <p><b>模型</b>：请求前 {@link #precheck} 校验余额是否覆盖估算成本（不足即 402），
 * 响应完成后 {@link #settle} 按实际 usage 扣费并落流水。估算与实扣的差额
 * 就是流式响应天然存在的不确定性——先扣够、多退少不补（实际用量小于估算时
 * 差额留在余额里，与 OpenAI 预扣费语义一致）。
 */
public interface BillingService {

    /** 按费率估算成本（micros）。模型未配费率返回 0（免费直通） */
    long estimateCost(String modelAlias, long promptTokens, long completionTokens);

    /**
     * 余额预检：当前余额是否覆盖本次估算成本。
     *
     * @return false 表示余额不足，调用方应拒绝请求（HTTP 402）
     */
    boolean precheck(long tenantId, long estimatedCostMicros);

    /**
     * 实际扣费：按费率 × 实际 usage 计算，原子扣减并写流水。
     * 幂等引用使用 traceId，重放投递不会重复扣款。
     *
     * @return 实扣金额（micros）；模型无费率返回 0
     */
    long settle(long tenantId, String traceId, String modelAlias,
                long promptTokens, long completionTokens);

    /** 当前余额（micros）。优先读内存缓存，未命中回源 DB */
    long balanceOf(long tenantId);

    /** 充值/赠送：余额增加并落流水，reason 决定流水类型 */
    void credit(long tenantId, long amountMicros, String reason, String ref);
}
