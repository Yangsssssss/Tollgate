package io.tollgate.upstream;

import io.tollgate.common.model.TokenUsage;

/**
 * 非流式调用的完整结果。
 *
 * @param content      模型输出全文
 * @param usage        token 用量，上游未返回时为 null
 * @param finishReason 结束原因，如 stop / length / tool_calls
 */
public record UpstreamResult(String content, TokenUsage usage, String finishReason) {
}
