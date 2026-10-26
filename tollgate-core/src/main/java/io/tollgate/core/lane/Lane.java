package io.tollgate.core.lane;

/**
 * 一条车道的运行时快照。调度内核在内存中维护它的可变状态（健康度、在途请求数），
 * 数据库只是它的持久化副本。
 *
 * @param id            车道主键
 * @param code          车道编码，全局唯一，用于日志与熔断归属
 * @param providerCode  上游厂商标识，与 Adapter 的 providerCode 对应
 * @param baseUrl       上游基础地址
 * @param priority      优先级，数值越小越优先；同优先级内按权重轮询
 * @param weight        权重，仅在同优先级层内生效
 * @param timeoutMillis 单次调用超时
 */
public record Lane(
        long id,
        String code,
        String providerCode,
        String baseUrl,
        int priority,
        int weight,
        long timeoutMillis
) {
}
