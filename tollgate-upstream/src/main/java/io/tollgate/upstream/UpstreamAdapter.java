package io.tollgate.upstream;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 上游适配器 SPI。新增一家厂商 = 新增一个实现类，注册为 Spring Bean，调度内核无需改动。
 *
 * <p>实现约定：
 * <ul>
 *   <li>只负责协议转换与 HTTP 调用，不做任何重试、限流、熔断——那是调度内核的职责</li>
 *   <li>流式实现必须把 usage 带到最后一个分片，否则计量只能退回字符估算</li>
 *   <li>抛出的异常统一包装为 {@code TollgateException}，并正确标记 retryable</li>
 * </ul>
 */
public interface UpstreamAdapter {

    /**
     * 适配器标识，需与 {@code provider.code} 一致，如 {@code openai}、{@code anthropic}。
     */
    String providerCode();

    /**
     * 非流式调用。
     */
    Mono<UpstreamResult> complete(UpstreamRequest request, LaneEndpoint endpoint);

    /**
     * 流式调用。返回增量分片，最后一帧必须携带 usage（若上游支持）。
     */
    Flux<UpstreamChunk> stream(UpstreamRequest request, LaneEndpoint endpoint);
}
