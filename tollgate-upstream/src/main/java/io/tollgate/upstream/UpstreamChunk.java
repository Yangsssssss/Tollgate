package io.tollgate.upstream;

import io.tollgate.common.model.TokenUsage;

/**
 * 流式响应的一帧。
 *
 * <p>约定：{@code usage} 只在最后一帧出现。调度层旁路收集，不阻塞转发。
 *
 * @param delta        本次增量文本，可能为空串（如仅心跳或仅 usage 帧）
 * @param usage        token 用量，非末帧为 null
 * @param finishReason 结束原因，非末帧为 null
 */
public record UpstreamChunk(String delta, TokenUsage usage, String finishReason) {

    public static UpstreamChunk text(String delta) {
        return new UpstreamChunk(delta, null, null);
    }

    public static UpstreamChunk terminal(String finishReason, TokenUsage usage) {
        return new UpstreamChunk("", usage, finishReason);
    }

    public boolean hasUsage() {
        return usage != null;
    }
}
