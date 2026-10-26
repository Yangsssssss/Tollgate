package io.tollgate.app.dataplane;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * token 本地估算。只用于两个场景：TPM 限流的预扣、上游未回 usage 时的兜底记账。
 * 真实用量永远以上游返回为准——估算值会打上 estimated 标记，对账时可见。
 *
 * <p><b>估算口径</b>：中英混合文本的经验值约 2.5 字符/token
 * （英文 ~4 chars/token，中文 ~1.5 chars/token），取 2.5 折中。
 * 限流场景允许 ±40% 的偏差——它挡的是量级，不是精确计费。
 */
public final class TokenEstimator {

    private TokenEstimator() {
    }

    /** 按字符数估算 prompt tokens，附加每条消息的固定开销（角色与格式约 4 token） */
    public static int estimatePrompt(JsonNode body) {
        long chars = 0;
        int messageCount = 0;
        JsonNode messages = body.path("messages");
        if (messages.isArray()) {
            for (JsonNode message : messages) {
                messageCount++;
                chars += message.path("content").asText("").length();
            }
        }
        long tokens = chars * 2 / 5 + messageCount * 4L;
        // 超大请求封顶，防止脏数据把 TPM 桶一击打穿
        return (int) Math.min(tokens, 1_000_000);
    }

    /** 限流预扣的总量：prompt 估算 + max_tokens（缺省 1024） */
    public static int estimateTotal(JsonNode body) {
        int maxTokens = body.path("max_tokens").asInt(1024);
        return estimatePrompt(body) + maxTokens;
    }

    /** 流式增量的 completion 兜底估算 */
    public static int estimateCompletion(int emittedChars) {
        return Math.max(1, emittedChars * 2 / 5);
    }
}
