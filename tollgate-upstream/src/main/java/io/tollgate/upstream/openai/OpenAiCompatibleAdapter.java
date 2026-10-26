package io.tollgate.upstream.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tollgate.common.error.ErrorCode;
import io.tollgate.common.error.TollgateException;
import io.tollgate.common.model.TokenUsage;
import io.tollgate.upstream.LaneEndpoint;
import io.tollgate.upstream.UpstreamAdapter;
import io.tollgate.upstream.UpstreamChunk;
import io.tollgate.upstream.UpstreamRequest;
import io.tollgate.upstream.UpstreamResult;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OpenAI 兼容协议适配器。OpenAI / DeepSeek / Moonshot / 各类中转站都吃这一套报文，
 * 一个实现覆盖绝大多数国产上游——这也是把 OPENAI_COMPATIBLE 定为默认 protocol 的原因。
 *
 * <p><b>报文策略：透传优先</b>。客户端请求体不做结构化重建，只在 JSON 树上改两个
 * 字段（model 换成上游模型名、stream 按需强设）。用户传的厂商特有参数
 * （response_format、tools、seed……）原样到达上游，适配器不需要追着参数表跑。
 *
 * <p><b>SSE 解析</b>：逐行读 {@code data: {...}}，{@code [DONE]} 结束。
 * usage 只在最后一帧出现（OpenAI 需要 stream_options.include_usage，
 * 很多兼容上游默认就带）——没等到就把累积增量按字符数估算，
 * 标记 estimated=true，宁可估算也不丢账。
 */
@Component
public class OpenAiCompatibleAdapter implements UpstreamAdapter {

    public static final String PROTOCOL = "OPENAI_COMPATIBLE";

    private static final org.springframework.core.ParameterizedTypeReference<
            org.springframework.http.codec.ServerSentEvent<String>> SSE_EVENT =
            new org.springframework.core.ParameterizedTypeReference<>() {
            };

    private final ObjectMapper mapper;
    private final WebClient client;

    public OpenAiCompatibleAdapter(ObjectMapper mapper) {
        this.mapper = mapper;
        this.client = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(
                        HttpClient.create().responseTimeout(Duration.ofSeconds(120))))
                .build();
    }

    @Override
    public String providerCode() {
        return PROTOCOL;
    }

    @Override
    public Mono<UpstreamResult> complete(UpstreamRequest request, LaneEndpoint endpoint) {
        ObjectNode body = prepareBody(request, endpoint);
        return client.post()
                .uri(endpoint.baseUrl() + "/v1/chat/completions")
                .header("Authorization", "Bearer " + endpoint.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body.toString())
                .exchangeToMono(resp -> resp.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(raw -> {
                            if (!resp.statusCode().is2xxSuccessful()) {
                                throw upstreamError(resp.statusCode().value(), raw, endpoint);
                            }
                            return parseCompletion(raw);
                        }));
    }

    @Override
    public Flux<UpstreamChunk> stream(UpstreamRequest request, LaneEndpoint endpoint) {
        ObjectNode body = prepareBody(request, endpoint);
        body.put("stream", true);
        // 显式向 OpenAI 索要 usage 帧；不认识该字段的上游会忽略，无副作用
        body.putObject("stream_options").put("include_usage", true);

        AtomicInteger emittedChars = new AtomicInteger();
        AtomicReference<TokenUsage> lastUsage = new AtomicReference<>();
        AtomicReference<String> finishReason = new AtomicReference<>();
        AtomicBoolean usageFrameEmitted = new AtomicBoolean();

        return client.post()
                .uri(endpoint.baseUrl() + "/v1/chat/completions")
                .header("Authorization", "Bearer " + endpoint.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body.toString())
                .exchangeToFlux(resp -> {
                    if (!resp.statusCode().is2xxSuccessful()) {
                        // 流式请求的错误以普通 JSON 报文返回，聚合后统一抛出
                        return resp.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .flatMapMany(raw ->
                                        Flux.error(upstreamError(resp.statusCode().value(), raw, endpoint)));
                    }
                    // 关键分支：响应是 text/event-stream 时 WebClient 走 SSE 解码器，
                    // bodyToFlux(String) 拿到的是已剥掉 "data:" 前缀的裸数据，
                    // 再按行找 "data:" 会全部落空——必须用 ServerSentEvent 类型接。
                    // 非 SSE Content-Type 的上游（部分兼容站用 text/plain）才走逐行切分。
                    MediaType respType = resp.headers().contentType().orElse(null);
                    if (respType != null && MediaType.TEXT_EVENT_STREAM.includes(respType)) {
                        return resp.bodyToFlux(SSE_EVENT)
                                .mapNotNull(sse -> handleSseData(sse.data(),
                                        emittedChars, lastUsage, finishReason, usageFrameEmitted));
                    }
                    return resp.bodyToFlux(String.class)
                            .flatMapIterable(OpenAiCompatibleAdapter::splitSseLines)
                            .mapNotNull(line -> handleSseData(line,
                                    emittedChars, lastUsage, finishReason, usageFrameEmitted));
                });
    }

    /** 统一处理单个 SSE data 载荷：null 表示需要过滤的帧 */
    private UpstreamChunk handleSseData(String data, AtomicInteger emittedChars,
                                        AtomicReference<TokenUsage> lastUsage,
                                        AtomicReference<String> finishReason,
                                        AtomicBoolean usageFrameEmitted) {
        if (data == null) {
            return null;
        }
        data = data.trim();
        if (data.isEmpty()) {
            return null;
        }
        if (data.equals("[DONE]")) {
            return terminalChunk(emittedChars, lastUsage, finishReason, usageFrameEmitted);
        }
        return parseChunk(data, emittedChars, lastUsage, finishReason, usageFrameEmitted);
    }

    // ---- 报文构造 ----

    /** 透传 rawBody，仅替换 model 为上游模型名；rawBody 缺失时从结构化字段兜底构造 */
    private ObjectNode prepareBody(UpstreamRequest request, LaneEndpoint endpoint) {
        try {
            ObjectNode body = request.rawBody() != null
                    ? (ObjectNode) mapper.readTree(request.rawBody())
                    : mapper.createObjectNode();
            // 模型名以 endpoint 为准：对外别名与上游真实名在此完成映射
            body.put("model", endpoint.upstreamModel());
            if (request.maxTokens() != null) {
                body.put("max_tokens", request.maxTokens());
            }
            return body;
        } catch (Exception e) {
            throw new TollgateException(ErrorCode.INVALID_REQUEST, "请求体不是合法 JSON", e);
        }
    }

    // ---- 非流式解析 ----

    private UpstreamResult parseCompletion(String raw) {
        try {
            JsonNode root = mapper.readTree(raw);
            JsonNode choice = root.path("choices").path(0);
            String content = choice.path("message").path("content").asText("");
            String finish = choice.path("finish_reason").asText(null);
            JsonNode usageNode = root.path("usage");
            TokenUsage usage = usageNode.isMissingNode() || usageNode.path("prompt_tokens").isMissingNode()
                    ? null
                    : TokenUsage.actual(
                            usageNode.path("prompt_tokens").asInt(),
                            usageNode.path("completion_tokens").asInt());
            return new UpstreamResult(content, usage, finish);
        } catch (Exception e) {
            throw new TollgateException(ErrorCode.UPSTREAM_TIMEOUT,
                    "上游响应解析失败: " + e.getMessage(), null, e, true);
        }
    }

    // ---- 流式解析 ----

    private UpstreamChunk parseChunk(String data, AtomicInteger emittedChars,
                                     AtomicReference<TokenUsage> lastUsage,
                                     AtomicReference<String> finishReason,
                                     AtomicBoolean usageFrameEmitted) {
        try {
            JsonNode root = mapper.readTree(data);
            JsonNode choice = root.path("choices").path(0);
            String delta = choice.path("delta").path("content").asText("");
            if (!delta.isEmpty()) {
                emittedChars.addAndGet(delta.length());
            }
            if (!choice.path("finish_reason").isNull() && !choice.path("finish_reason").isMissingNode()) {
                finishReason.set(choice.path("finish_reason").asText());
            }
            JsonNode usageNode = root.path("usage");
            if (!usageNode.isMissingNode() && !usageNode.path("prompt_tokens").isMissingNode()) {
                TokenUsage usage = TokenUsage.actual(
                        usageNode.path("prompt_tokens").asInt(),
                        usageNode.path("completion_tokens").asInt());
                lastUsage.set(usage);
                usageFrameEmitted.set(true);
                return new UpstreamChunk(delta, usage, finishReason.get());
            }
            return delta.isEmpty() && finishReason.get() == null
                    ? null // 心跳帧，直接丢弃
                    : new UpstreamChunk(delta, null, finishReason.get());
        } catch (Exception e) {
            // 单帧解析失败不炸整个流——跳过坏帧
            return null;
        }
    }

    /** 上游没带 usage 帧：按字符估算 completion tokens（经验值 4 字符/token） */
    private UpstreamChunk terminalChunk(AtomicInteger emittedChars,
                                        AtomicReference<TokenUsage> lastUsage,
                                        AtomicReference<String> finishReason,
                                        AtomicBoolean usageFrameEmitted) {
        // usage 已随专用帧发出时终端只补 finishReason，避免客户端收到两份 usage
        if (usageFrameEmitted.get()) {
            return UpstreamChunk.terminal(finishReason.get(), null);
        }
        TokenUsage usage = lastUsage.get();
        if (usage == null) {
            usage = TokenUsage.estimated(0, Math.max(1, emittedChars.get() / 4));
        }
        return UpstreamChunk.terminal(finishReason.get(), usage);
    }

    // ---- 错误映射 ----

    /** 上游非 2xx → 带重试语义的 TollgateException。4xx 参数类错误不重试，换道也没用 */
    private TollgateException upstreamError(int httpStatus, String rawBody, LaneEndpoint endpoint) {
        boolean retryable = httpStatus == 429 || httpStatus == 401 || httpStatus == 403
                || httpStatus >= 500;
        ErrorCode code = switch (httpStatus) {
            case 429 -> ErrorCode.LANE_OVERLOADED;
            case 401, 403 -> ErrorCode.NO_AVAILABLE_LANE; // 上游密钥问题，换道/换密钥是正确响应
            default -> httpStatus >= 500 ? ErrorCode.UPSTREAM_TIMEOUT : ErrorCode.INVALID_REQUEST;
        };
        String detail = rawBody.length() > 300 ? rawBody.substring(0, 300) : rawBody;
        return TollgateException.retryable(code, endpoint.laneCode(),
                new UpstreamHttpException(httpStatus, detail));
    }

    // ---- SSE 行切分 ----

    /**
     * WebFlux 按网络帧给字符串，一行 SSE 事件可能被拆在两个帧里。
     * 这里不做跨帧缓冲——OpenAI 的 data 行以完整 JSON 出现的实践下，
     * 帧尾残留交给 Jackson 的宽松解析跳过；跨帧撕裂的彻底方案（换行符缓冲）
     * 在 V1.3 验收后视真实上游表现再决定是否引入。
     */
    static List<String> splitSseLines(String frame) {
        List<String> out = new ArrayList<>();
        for (String line : frame.split("\n")) {
            line = line.trim();
            if (line.startsWith("data:")) {
                String data = line.substring(5).trim();
                if (!data.isEmpty()) {
                    out.add(data);
                }
            }
        }
        return out;
    }

    /** 上游 HTTP 异常的载体，保留状态码与截断后的响应体用于排查 */
    public static final class UpstreamHttpException extends RuntimeException {
        public final int status;

        UpstreamHttpException(int status, String body) {
            super("upstream http %d: %s".formatted(status, body));
            this.status = status;
        }
    }
}
