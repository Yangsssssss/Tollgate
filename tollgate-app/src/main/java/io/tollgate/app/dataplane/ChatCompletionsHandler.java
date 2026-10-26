package io.tollgate.app.dataplane;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tollgate.app.dataplane.CatalogSnapshot.ApiKeyCredential;
import io.tollgate.app.metering.BatchingTollRecorder;
import io.tollgate.common.error.ErrorCode;
import io.tollgate.common.error.TollgateException;
import io.tollgate.common.model.TokenUsage;
import io.tollgate.core.dispatch.LaneSelection;
import io.tollgate.core.lane.Lane;
import io.tollgate.metering.TollRecord;
import io.tollgate.upstream.LaneEndpoint;
import io.tollgate.upstream.UpstreamAdapter;
import io.tollgate.upstream.UpstreamChunk;
import io.tollgate.upstream.UpstreamRequest;
import io.tollgate.upstream.UpstreamResult;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 数据面核心处理器：POST /v1/chat/completions 的完整生命周期。
 *
 * <pre>
 * 认证 → 解析 → 限流 → 选路 → 转发（流式/非流式）→ 失败换道重试 → 计量落账
 * </pre>
 *
 * <p><b>换道重试的边界</b>：非流式在任何错误后都可换道；流式只在<b>首帧发出前</b>
 * 可换道——一旦有数据到达客户端，连接语义已提交，中断换道会让客户端收到
 * 半截回答拼上一个新回答，比报错更糟。
 *
 * <p><b>计量的位置</b>：usage 采集与车道成败上报都挂在响应流的终端事件上
 * （onComplete/onError），与转发并行，不阻塞任何一帧数据。
 */
@Component
public class ChatCompletionsHandler {

    private static final int MAX_ATTEMPTS = 3;

    private final DispatchRuntime runtime;
    private final BatchingTollRecorder recorder;
    private final Map<String, UpstreamAdapter> adapters;
    private final ObjectMapper mapper;

    public ChatCompletionsHandler(DispatchRuntime runtime, BatchingTollRecorder recorder,
                                  List<UpstreamAdapter> adapters, ObjectMapper mapper) {
        this.runtime = runtime;
        this.recorder = recorder;
        this.mapper = mapper;
        this.adapters = adapters.stream()
                .collect(Collectors.toMap(UpstreamAdapter::providerCode, a -> a));
    }

    public Mono<ServerResponse> chatCompletions(ServerRequest request) {
        long startMillis = System.currentTimeMillis();
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 24);

        String token = bearerToken(request);
        if (token == null) {
            return reject(ErrorCode.INVALID_API_KEY, "缺少 Authorization: Bearer <key>");
        }
        ApiKeyCredential cred;
        try {
            cred = runtime.authenticate(sha256Hex(token));
        } catch (TollgateException e) {
            return reject(e.getErrorCode(), e.getMessage());
        }

        return request.bodyToMono(String.class)
                .switchIfEmpty(Mono.error(new TollgateException(ErrorCode.INVALID_REQUEST, "请求体为空")))
                .flatMap(rawBody -> {
                    JsonNode body;
                    try {
                        body = mapper.readTree(rawBody);
                    } catch (Exception e) {
                        return reject(ErrorCode.INVALID_REQUEST, "请求体不是合法 JSON");
                    }
                    String model = body.path("model").asText(null);
                    boolean stream = body.path("stream").asBoolean(false);
                    if (model == null || body.path("messages").isEmpty()) {
                        return reject(ErrorCode.INVALID_REQUEST, "model 与 messages 为必填");
                    }
                    try {
                        runtime.checkRate(cred.tenantId(), TokenEstimator.estimateTotal(body));
                    } catch (TollgateException e) {
                        return reject(e.getErrorCode(), e.getMessage());
                    }
                    if (!runtime.hasRoutes(model)) {
                        return reject(ErrorCode.MODEL_NOT_FOUND, "模型不存在或无可用车道: " + model);
                    }
                    var ctx = new DispatchContext(traceId, cred, model, body, stream,
                            TokenEstimator.estimateTotal(body), startMillis, rawBody);
                    return stream ? streamDispatch(ctx, 1) : completeDispatch(ctx, 1);
                })
                .onErrorResume(TollgateException.class, e -> reject(e.getErrorCode(), e.getMessage()));
    }

    // ------------------------------------------------------------------
    // 非流式
    // ------------------------------------------------------------------

    private Mono<ServerResponse> completeDispatch(DispatchContext ctx, int attempt) {
        long attemptStart = System.currentTimeMillis();
        LaneSelection selection;
        try {
            selection = runtime.select(ctx.cred().tenantCode(), ctx.model(), ctx.estimatedTokens());
        } catch (TollgateException e) {
            return reject(e.getErrorCode(), e.getMessage());
        }
        Lane lane = selection.lane();
        LaneEndpoint endpoint = endpoint(ctx, lane, selection);
        UpstreamAdapter adapter = adapterFor(lane);
        UpstreamRequest upstreamRequest = upstreamRequest(ctx, false);

        return adapter.complete(upstreamRequest, endpoint)
                .flatMap(result -> {
                    long latency = System.currentTimeMillis() - attemptStart;
                    selection.succeed();
                    logAttempt(ctx, lane.code(), attempt, "SUCCESS", 200, null, latency, false);
                    TokenUsage usage = result.usage() != null ? result.usage()
                            : TokenUsage.estimated(
                                    TokenEstimator.estimatePrompt(ctx.body()),
                                    TokenEstimator.estimateCompletion(result.content().length()));
                    recordToll(ctx, lane.code(), attempt, usage, latency, true);
                    return ServerResponse.ok()
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(completionResponse(ctx, result, usage));
                })
                .onErrorResume(TollgateException.class, e -> {
                    long latency = System.currentTimeMillis() - attemptStart;
                    selection.fail();
                    logAttempt(ctx, lane.code(), attempt, "FAILED",
                            upstreamStatus(e), e.getErrorCode().getCode(), latency, false);
                    if (e.isRetryable() && attempt < MAX_ATTEMPTS) {
                        return completeDispatch(ctx, attempt + 1);
                    }
                    recordToll(ctx, lane.code(), attempt, TokenUsage.ZERO, latency, false);
                    return reject(e.getErrorCode(), friendlyMessage(e, attempt));
                });
    }

    // ------------------------------------------------------------------
    // 流式
    // ------------------------------------------------------------------

    private Mono<ServerResponse> streamDispatch(DispatchContext ctx, int attempt) {
        var committed = new AtomicBoolean();
        var usageRef = new AtomicReference<TokenUsage>();

        Flux<UpstreamChunk> guarded = streamAttempt(ctx, attempt, committed, usageRef)
                .doOnNext(chunk -> {
                    if (chunk.hasUsage()) {
                        usageRef.set(chunk.usage());
                    }
                });

        Flux<ServerSentEvent<String>> body = guarded
                .map(chunk -> ServerSentEvent.builder(chunkJson(ctx, chunk)).build())
                .concatWith(Flux.just(ServerSentEvent.builder("[DONE]").build()));

        return ServerResponse.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(body, ServerSentEvent.class)
                // 流一旦开始响应，后续错误已无法转换成新的 HTTP 状态，
                // 只能靠 SSE 内部约定（这里选择直接断流，客户端 SDK 会识别）
                .onErrorResume(TollgateException.class, e ->
                        reject(e.getErrorCode(), friendlyMessage(e, attempt)));
    }

    private Flux<UpstreamChunk> streamAttempt(DispatchContext ctx, int attempt,
                                              AtomicBoolean committed,
                                              AtomicReference<TokenUsage> usageRef) {
        long attemptStart = System.currentTimeMillis();
        LaneSelection selection;
        try {
            selection = runtime.select(ctx.cred().tenantCode(), ctx.model(), ctx.estimatedTokens());
        } catch (TollgateException e) {
            return Flux.error(e);
        }
        Lane lane = selection.lane();
        LaneEndpoint endpoint = endpoint(ctx, lane, selection);
        UpstreamAdapter adapter = adapterFor(lane);

        return adapter.stream(upstreamRequest(ctx, true), endpoint)
                .doOnNext(chunk -> committed.set(true))
                .doOnComplete(() -> {
                    long latency = System.currentTimeMillis() - attemptStart;
                    selection.succeed();
                    logAttempt(ctx, lane.code(), attempt, "SUCCESS", 200, null, latency, true);
                    TokenUsage usage = usageRef.get() != null ? usageRef.get()
                            : TokenUsage.estimated(TokenEstimator.estimatePrompt(ctx.body()), 1);
                    recordToll(ctx, lane.code(), attempt, usage, latency, true);
                })
                .onErrorResume(e -> {
                    long latency = System.currentTimeMillis() - attemptStart;
                    selection.fail();
                    TollgateException te = e instanceof TollgateException t ? t
                            : TollgateException.retryable(ErrorCode.UPSTREAM_TIMEOUT, lane.code(), e);
                    logAttempt(ctx, lane.code(), attempt, "FAILED",
                            upstreamStatus(te), te.getErrorCode().getCode(), latency, true);
                    boolean canRetry = te.isRetryable() && !committed.get() && attempt < MAX_ATTEMPTS;
                    if (canRetry) {
                        return streamAttempt(ctx, attempt + 1, committed, usageRef);
                    }
                    recordToll(ctx, lane.code(), attempt, TokenUsage.ZERO, latency, false);
                    return Flux.error(te);
                });
    }

    // ------------------------------------------------------------------
    // 组装辅助
    // ------------------------------------------------------------------

    private record DispatchContext(
            String traceId,
            ApiKeyCredential cred,
            String model,
            JsonNode body,
            boolean stream,
            long estimatedTokens,
            long startMillis,
            String rawBody
    ) {
    }

    private LaneEndpoint endpoint(DispatchContext ctx, Lane lane, LaneSelection selection) {
        return new LaneEndpoint(
                lane.baseUrl(),
                selection.key().secret(),
                runtime.upstreamModelOf(ctx.model(), lane.id()),
                lane.code());
    }

    private UpstreamAdapter adapterFor(Lane lane) {
        UpstreamAdapter adapter = adapters.get(lane.providerCode());
        if (adapter == null) {
            throw new TollgateException(ErrorCode.INTERNAL_ERROR,
                    "无适配器支持协议: " + lane.providerCode());
        }
        return adapter;
    }

    private UpstreamRequest upstreamRequest(DispatchContext ctx, boolean stream) {
        JsonNode body = ctx.body();
        return new UpstreamRequest(
                "", // model 由 endpoint.upstreamModel 在适配器内覆盖，此处占位
                List.of(),
                body.has("temperature") ? body.path("temperature").asDouble() : null,
                body.has("max_tokens") ? body.path("max_tokens").asInt() : null,
                stream,
                ctx.rawBody());
    }

    // ------------------------------------------------------------------
    // 报文构造
    // ------------------------------------------------------------------

    /** 非流式：以网关自身的结构重建 OpenAI 兼容响应，usage 一定存在 */
    private String completionResponse(DispatchContext ctx, UpstreamResult result, TokenUsage usage) {
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("id", "chatcmpl-" + ctx.traceId());
            root.put("object", "chat.completion");
            root.put("created", Instant.now().getEpochSecond());
            root.put("model", ctx.model());
            ArrayNode choices = root.putArray("choices");
            ObjectNode choice = choices.addObject();
            choice.put("index", 0);
            choice.putObject("message")
                    .put("role", "assistant")
                    .put("content", result.content());
            choice.put("finish_reason", result.finishReason() != null ? result.finishReason() : "stop");
            ObjectNode usageNode = root.putObject("usage");
            usageNode.put("prompt_tokens", usage.promptTokens());
            usageNode.put("completion_tokens", usage.completionTokens());
            usageNode.put("total_tokens", usage.total());
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new TollgateException(ErrorCode.INTERNAL_ERROR, "响应序列化失败", e);
        }
    }

    /** 流式帧：增量与 usage 各按 OpenAI chunk 结构输出 */
    private String chunkJson(DispatchContext ctx, UpstreamChunk chunk) {
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("id", "chatcmpl-" + ctx.traceId());
            root.put("object", "chat.completion.chunk");
            root.put("created", Instant.now().getEpochSecond());
            root.put("model", ctx.model());
            ArrayNode choices = root.putArray("choices");
            if (chunk.hasUsage()) {
                // usage 帧：choices 为空数组，usage 单独携带（与 OpenAI include_usage 行为一致）
                ObjectNode usageNode = root.putObject("usage");
                usageNode.put("prompt_tokens", chunk.usage().promptTokens());
                usageNode.put("completion_tokens", chunk.usage().completionTokens());
                usageNode.put("total_tokens", chunk.usage().total());
                return mapper.writeValueAsString(root);
            }
            ObjectNode choice = choices.addObject();
            choice.put("index", 0);
            ObjectNode delta = choice.putObject("delta");
            if (chunk.finishReason() != null) {
                choice.put("finish_reason", chunk.finishReason());
            } else {
                delta.put("content", chunk.delta());
                choice.putNull("finish_reason");
            }
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new TollgateException(ErrorCode.INTERNAL_ERROR, "分片序列化失败", e);
        }
    }

    // ------------------------------------------------------------------
    // 落账
    // ------------------------------------------------------------------

    private void recordToll(DispatchContext ctx, String laneCode, int attempt,
                            TokenUsage usage, long latencyMillis, boolean success) {
        recorder.record(new TollRecord(
                ctx.traceId(), ctx.cred().tenantId(), ctx.model(), laneCode,
                usage, 0L, latencyMillis, attempt - 1, success, Instant.now()));
    }

    private void logAttempt(DispatchContext ctx, String laneCode, int attempt, String status,
                            Integer httpStatus, String errorCode, long latency, boolean streamed) {
        recorder.logRequest(new BatchingTollRecorder.RequestLogEntry(
                ctx.traceId(), ctx.cred().tenantId(), ctx.cred().keyId(), ctx.model(),
                laneCode, attempt, status, httpStatus, errorCode, latency, streamed));
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private Mono<ServerResponse> reject(ErrorCode code, String message) {
        return ServerResponse.status(code.getHttpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(ErrorResponses.of(code, message));
    }

    private static String bearerToken(ServerRequest request) {
        String header = request.headers().firstHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        String token = header.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    private static String sha256Hex(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static int upstreamStatus(TollgateException e) {
        Throwable cause = e.getCause();
        if (cause instanceof io.tollgate.upstream.openai.OpenAiCompatibleAdapter.UpstreamHttpException h) {
            return h.status;
        }
        return null == e.getErrorCode() ? 500 : e.getErrorCode().getHttpStatus();
    }

    private static String friendlyMessage(TollgateException e, int attempt) {
        return attempt > 1
                ? e.getMessage() + "（已换道重试 " + (attempt - 1) + " 次）"
                : e.getMessage();
    }
}
