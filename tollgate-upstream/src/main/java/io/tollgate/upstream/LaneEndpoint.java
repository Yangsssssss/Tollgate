package io.tollgate.upstream;

/**
 * 一条车道的调用端点：把"往哪儿发、用什么身份、发成什么模型名"三件事打包。
 *
 * <p>由调度内核在选中车道与密钥后构造，适配器只消费不加工。
 *
 * @param baseUrl       上游基础地址，如 {@code https://api.openai.com}
 * @param apiKey        上游真实密钥（从密钥池取出，绝不落日志）
 * @param upstreamModel 上游认的模型名，可能与对外模型别名不同
 * @param laneCode      车道编码，用于日志关联与熔断归属
 */
public record LaneEndpoint(
        String baseUrl,
        String apiKey,
        String upstreamModel,
        String laneCode
) {

    /** 避免密钥进日志：默认 record 的 toString 会把它打出来 */
    @Override
    public String toString() {
        return "LaneEndpoint[lane=%s, baseUrl=%s, upstreamModel=%s, apiKey=***]"
                .formatted(laneCode, baseUrl, upstreamModel);
    }
}
