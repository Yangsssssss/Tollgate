package io.tollgate.common.model;

/**
 * token 用量。所有计量的唯一数据口径。
 *
 * @param promptTokens     输入 token 数
 * @param completionTokens 输出 token 数
 * @param estimated        true 表示上游未返回 usage，数值由本地估算得出
 */
public record TokenUsage(int promptTokens, int completionTokens, boolean estimated) {

    public static final TokenUsage ZERO = new TokenUsage(0, 0, false);

    public int total() {
        return promptTokens + completionTokens;
    }

    public static TokenUsage actual(int prompt, int completion) {
        return new TokenUsage(prompt, completion, false);
    }

    public static TokenUsage estimated(int prompt, int completion) {
        return new TokenUsage(prompt, completion, true);
    }

    public TokenUsage plus(TokenUsage other) {
        return new TokenUsage(
                this.promptTokens + other.promptTokens,
                this.completionTokens + other.completionTokens,
                this.estimated || other.estimated
        );
    }
}
